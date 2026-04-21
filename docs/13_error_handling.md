# Error Handling

## Design Philosophy

AxionAuto uses a **typed exception hierarchy** that maps business domain failures to specific error codes. Errors are propagated either:
1. **Up the HTTP chain** — converted to HTTP status codes by the controller
2. **To the DLQ** — parked for operator inspection when processing is unrecoverable
3. **To execution logs** — recorded in `automation_execution_log` with status=`FAILED`

The goal is zero swallowed exceptions on critical paths — every failure either surfaces as an HTTP error (so external systems can retry) or is tracked durably.

---

## Exception Types

### `OAuthException`

Thrown during the OAuth 2.0 flow when Meta rejects the flow or the application detects a CSRF attack.

```java
public class OAuthException extends RuntimeException {
    private final OAuthErrorCode errorCode;

    public enum OAuthErrorCode {
        INVALID_STATE,          // state token missing/expired in Redis (CSRF detected)
        CODE_EXCHANGE_FAILED,   // Meta rejected the authorization code
        TOKEN_EXTENSION_FAILED, // failed to get long-lived token
        UNEXPECTED_ERROR        // serialization failure, etc.
    }
}
```

**When thrown:** `InstagramOAuthService.generateAuthorizationUrl()`, `handleCallback()`
**HTTP Response:** `400 Bad Request`
**Not retried** by Resilience4j.

---

### `TransientApiException`

Signals a recoverable failure when calling the Meta Graph API. Triggers Resilience4j retry (up to 4 attempts with exponential backoff: 1s → 2s → 4s).

```java
public class TransientApiException extends RuntimeException {
    // Created when Meta returns 5xx or network timeout occurs
}
```

**When thrown:** `MetaGraphApiClient` on HTTP 5xx or connection timeout
**Resilience4j:** retried (up to 4 attempts, 1s→2s→4s exponential backoff)

---

### `PermanentApiException`

Signals a non-recoverable Meta API failure. Not retried — retrying would never succeed.

```java
public class PermanentApiException extends RuntimeException {
    // Created for 400/403 from Meta, or missing token scenarios
}
```

**Examples:** Token revoked (403), invalid recipient IGSID (400), no token found for account

**When thrown:** `MetaGraphApiClient` on HTTP 4xx, `RuleReplyDispatcher` on missing/invalid token
**Not retried** by Resilience4j.
**Result:** `ExecutionStatus.FAILED` in `automation_execution_log`

---

### `RateLimitExceededException`

Thrown by `GraphApiRateLimiter` when an IG account exceeds 200 DM sends/hour.

```java
public class RateLimitExceededException extends RuntimeException {
    // Message: "Rate limit exceeded for Instagram Account {id} (200/200 in last 3600s)"
}
```

**When thrown:** `GraphApiRateLimiter.acquireOrThrow()` before any Graph API call
**Not retried** — retrying immediately would not solve an hourly rate limit.
**Result:** `ExecutionStatus.FAILED`

---

### `WebhookParseException`

Thrown if the raw webhook payload cannot be parsed or deserialized.

```java
public class WebhookParseException extends RuntimeException {
    private final String rawEventId; // for DLQ correlation
}
```

**When thrown:** `WebhookPayloadParser.parse()` on malformed JSON
**Handling:** Consumer catches this and parks the event to DLQ with code `DESERIALIZATION_ERROR`

---

## Error Flows by Layer

### Layer 1: HTTP Ingestion (WebhookController)

```
POST /webhooks/instagram
     │
     ├─ Invalid HMAC         → IngestResult.REJECTED  → HTTP 403
     ├─ Duplicate eventId    → IngestResult.DUPLICATE → HTTP 200
     ├─ Redis XADD fails     → IngestResult.QUEUE_FAILED → HTTP 500  ← Meta retries
     └─ Success              → IngestResult.ACCEPTED  → HTTP 200
```

Key: returning HTTP 500 to Meta causes Meta to re-deliver the webhook. This is intentional when Redis is temporarily unavailable — it prevents event loss.

---

### Layer 2: Stream Consumer (WebhookStreamConsumer)

```
onMessage(record)
     │
     ├─ JSON deserialization fails        → parkToDlq(DESERIALIZATION_ERROR)
     ├─ No active token for IG account    → parkToDlq(NO_TENANT_TOKEN)
     ├─ Normalization returns empty list  → parkToDlq(EMPTY_NORMALIZATION)
     ├─ Validation fails (per-DTO)        → parkToDlq(error code from validator)
     └─ Orchestration throws exception    → parkToDlq(CONSUMER_ERROR)
          └─ record NOT acknowledged (stays in PEL for future reclaim)
```

---

### Layer 3: Automation Engine (AutomationEngine)

```
executeOne(tenantId, message, rule)
     │
     ├─ Within cooldown window            → ExecutionStatus.SKIPPED
     ├─ replyDispatcher.dispatch() fails  → ExecutionStatus.FAILED
     │     ├─ Resilience4j retries (×4, exponential backoff)
     │     ├─ Retries exhausted           → PermanentApiException propagates
     │     └─ Circuit breaker OPEN        → fallbackMethod() throws immediately
     └─ Success                           → ExecutionStatus.SENT
```

---

### Layer 4: DLQ

Events that reach the DLQ are tracked with:

| Error Code | Cause |
|---|---|
| `DESERIALIZATION_ERROR` | Invalid JSON payload |
| `NO_TENANT_TOKEN` | No ACTIVE token found for the IG account |
| `EMPTY_NORMALIZATION` | Parser returned zero valid MessageDTOs |
| `MISSING_SENDER_ID` | senderId is blank |
| `INVALID_SENDER_ID` | senderId is not a valid IGSID |
| `TIMESTAMP_IN_FUTURE` | Event timestamp > 30s ahead of server clock |
| `TIMESTAMP_TOO_OLD` | Event older than 24 hours |
| `UNKNOWN_MESSAGE_TYPE` | Cannot route — no rule type handles this |
| `TEXT_TOO_LONG` | Message text > 2048 characters |
| `CONSUMER_ERROR` | Uncaught exception in consumer processing |

---

## Resilience4j Error Recovery

### Retry Pattern

```
Call sendMessage()
  │
  ├─ Attempt 1 (0ms wait):     ResourceAccessException / TransientApiException → retry
  ├─ Attempt 2 (1s wait):      Same → retry
  ├─ Attempt 3 (2s wait):      Same → retry
  ├─ Attempt 4 (4s wait):      Same → retry
  └─ All attempts failed       → last exception propagates → ExecutionStatus.FAILED
```

`PermanentApiException` and `OAuthException` immediately bypass the retry loop.

### Circuit Breaker Behavior

```
CLOSED → normal operation
  if failure rate > 50% in last 10 calls → transition to OPEN
OPEN   → all calls rejected immediately via fallbackMethod
  after 30s → transition to HALF_OPEN
HALF_OPEN → 3 probe calls
  if probes succeed → transition to CLOSED
  if probes fail    → transition to OPEN again
```

The circuit breaker state is exposed via Spring Actuator health indicator:
```
GET /actuator/health
→ { "status": "UP", "components": { "circuitBreakers": { "meta-graph-api": "CLOSED" } } }
```

---

## WebhookEventStatusUpdater — Transactional Isolation

Status updates use `REQUIRES_NEW` propagation:

```java
@Transactional(propagation = REQUIRES_NEW)
public void markFailed(String eventId, String reason) { ... }
```

This ensures that even if the outer processing transaction is rolled back (e.g., a DB exception mid-execution), the `FAILED` status is still committed to the audit table. Without this, the event would remain in `PROCESSING` state indefinitely.

---

## Dead Letter Queue Recovery

When an event is in the DLQ:

1. **Inspect**: `GET /api/v1/webhooks/instagram/dlq` — see what failed and why
2. **Fix the root cause** (e.g., re-connect the Instagram account that had no token)
3. **Replay**: `POST /api/v1/webhooks/instagram/dlq/replay?limit=10`
   - Fetches original payload from DB (`instagram_webhook_events.payload`)
   - Re-adds to `instagram-webhooks` stream with `"replay":"true"`
   - Original idempotency guard deduplicates based on `eventId` — if the event was already processed, it is safely rejected
   - Deletes entry from DLQ after successful re-add
4. **Skip unresolvable events**: If no payload found in DB → event is removed from DLQ with a warning log (cannot replay without payload)

---

## Frontend Error Handling

- API errors are caught globally and shown as `toast-error` notifications via `addToast(message, 'error')` in `AppContext`
- Network failures display a fallback error state component
- Loading states use `.skeleton` shimmer classes while data is being fetched
