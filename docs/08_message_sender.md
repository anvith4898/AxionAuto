# Message Sender — Graph API Dispatch

## Overview

The `RuleReplyDispatcher` and `InstagramMessageSenderService` form the final stage of the automation pipeline. After the engine selects a rule and resolves the reply text, this layer is responsible for:

1. Resolving and decrypting the tenant's access token
2. Acquiring a rate-limit slot
3. Calling the Meta Graph API to send the DM
4. Retrying on transient failures
5. Circuit-breaking on sustained Meta API failures

---

## RuleReplyDispatcher

Bridges the automation engine to the message sender. Scoped at the account level — it resolves the decrypted token before passing it down.

```java
@Service
@RequiredArgsConstructor
public class RuleReplyDispatcher {

    private final InstagramOAuthTokenRepository tokenRepository;
    private final TokenEncryptionService        encryptionService;
    private final InstagramMessageSenderService senderService;

    public void dispatch(UUID tenantId, MessageDTO message, AutomationRule rule, String resolvedReply) {

        // 1. Resolve the access token for this account
        InstagramOAuthToken token = tokenRepository
            .findByTenantIdAndInstagramAccountId(tenantId, message.igAccountId())
            .orElseThrow(() -> new PermanentApiException("No token found for account"));

        // 2. Validate token status
        if (token.getStatus() != ACTIVE || token.isExpired()) {
            throw new PermanentApiException("Token is not active: " + token.getStatus());
        }

        // 3. Decrypt the access token
        String plainTextToken = encryptionService.decrypt(
            token.getAccessTokenEncrypted(),
            token.getAccessTokenIv(),
            token.getAccessTokenTag()
        );

        // 4. Build the send request
        MessageSendRequest request = new MessageSendRequest(
            message.igAccountId(),   // sending account
            message.senderId(),      // recipient IGSID
            resolvedReply
        );

        // 5. Send via the API (Resilience4j wraps this)
        senderService.sendMessage(request, plainTextToken);
    }
}
```

---

## InstagramMessageSenderService

Handles the actual HTTP call to the Meta Graph API. Protected by Resilience4j annotations for automatic retry and circuit breaking.

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class InstagramMessageSenderService {

    private final MetaGraphApiClient   graphApiClient;
    private final GraphApiRateLimiter  rateLimiter;

    @Retry(name = "meta-graph-api")
    @CircuitBreaker(name = "meta-graph-api", fallbackMethod = "sendMessageFallback")
    public MessageSendResponse sendMessage(MessageSendRequest request, String accessToken) {

        // 1. Rate limit check (throws RateLimitExceededException if over limit)
        rateLimiter.acquireOrThrow(request.igAccountId());

        // 2. Call the Graph API
        log.info("[sender] Sending reply to senderId={} from igAccountId={}",
                request.recipientId(), request.igAccountId());

        return graphApiClient.sendMessage(request.igAccountId(), accessToken, request);
    }

    // Fallback is called if the circuit breaker is OPEN
    private MessageSendResponse sendMessageFallback(
            MessageSendRequest request, String token, Throwable cause) {
        log.error("[sender] Circuit breaker OPEN for meta-graph-api. igAccountId={}, cause={}",
                request.igAccountId(), cause.getMessage());
        throw new PermanentApiException("Circuit breaker open: " + cause.getMessage());
    }
}
```

---

## Resilience4j Configuration

Configured in `application.yml`:

```yaml
resilience4j:
  retry:
    instances:
      meta-graph-api:
        max-attempts: 4
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2.0
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - com.axion.auth.exception.TransientApiException
        ignore-exceptions:
          - com.axion.auth.exception.PermanentApiException
          - com.axion.auth.exception.OAuthException

  circuitbreaker:
    instances:
      meta-graph-api:
        register-health-indicator: true
        sliding-window-size: 10
        failure-rate-threshold: 50        # 50% failure rate triggers OPEN
        wait-duration-in-open-state: 30s  # wait 30s before trying HALF_OPEN
        permitted-number-of-calls-in-half-open-state: 3
```

### Retry Behavior

| Attempt | Wait Before | Notes |
|---|---|---|
| 1st | Immediate | First try |
| 2nd | 1s | On `TransientApiException` or `ResourceAccessException` |
| 3rd | 2s | Exponential backoff (×2) |
| 4th | 4s | Final attempt |
| Exhausted | Propagate exception | `FAILED` execution log, DLQ not involved |

**Not retried:** `PermanentApiException` (e.g., HTTP 403 token revoked, HTTP 400 invalid recipient) and `OAuthException`. These would never succeed on retry.

### Circuit Breaker States

```
            failure rate > 50%
    CLOSED ─────────────────────► OPEN
       ▲                            │
       │           30s timeout      │
       │          ─────────────►    │
       │                       HALF_OPEN
       │                            │
       │     3 probe calls succeed  │
       └────────────────────────────┘
```

- **CLOSED**: all calls go through normally
- **OPEN**: all calls fail immediately with `fallbackMethod` (no real API calls)
- **HALF_OPEN**: 3 probe calls allowed; if they succeed → CLOSED; if they fail → OPEN again

The circuit breaker protects against sustained Meta API outages cascading into thread exhaustion.

---

## MetaGraphApiClient — Graph API HTTP Client

Low-level wrapper using Spring's `RestClient`:

```java
// Graph API: send a DM
POST /{igAccountId}/messages
  Authorization: Bearer {accessToken}
  Content-Type: application/json
  Body: {
    "recipient": { "id": "{recipientIgsid}" },
    "message":   { "text": "{replyText}" }
  }

→ 200 OK: { "recipient_id": "...", "message_id": "mid.xxx" }
→ 400:    permanent error (invalid recipient or malformed request)
→ 403:    token revoked or insufficient permissions
→ 429:    rate limited by Meta (edge case; should be caught by GraphApiRateLimiter first)
→ 5xx:    transient Meta error (trigger Resilience4j retry)
```

HTTP status → exception mapping:
- `4xx` responses that indicate permanent failure → `PermanentApiException` (not retried)
- `5xx` responses → `TransientApiException` (retried up to 4 times)
- Network timeout / connection refused → `ResourceAccessException` → retried

---

## MessageSendRequest / MessageSendResponse DTOs

```java
// Request
public record MessageSendRequest(
    String igAccountId,  // The IG Business Account doing the sending
    String recipientId,  // The user's IGSID
    String text          // The reply text (template already resolved)
) {}

// Response
public record MessageSendResponse(
    String recipientId,  // echoed back by Meta
    String messageId     // Meta's assigned message ID for the outbound message
) {}
```

---

## GraphApiRateLimiter

Protects against exceeding Instagram's DM send quota of **200 calls/hour per access token**.

### Algorithm: Redis Sorted-Set Sliding Window

```
Key:    rate_limit:ig_account:{instagramAccountId}
Score:  epoch milliseconds (unique per call)
Member: epoch milliseconds (same as score)

Per request:
1. ZREMRANGEBYSCORE key 0 (nowMs - 3600000)   ← evict entries older than 1 hour
2. ZADD key {nowMs} {nowMs}                    ← record this request
3. EXPIRE key 3610                             ← TTL = window + 10s for cleanup
4. ZCARD key                                   ← count in-window requests
5. if count > 200:
     ZREM key {nowMs}                          ← undo — don't count rejected calls
     throw RateLimitExceededException
```

**Why a sliding window instead of a fixed bucket?**
A fixed per-second key allows "boundary bursts": 200 requests in the last 10ms of second T, then 200 more in the first 10ms of T+1 — effectively 400 requests in 20ms. The sliding window always measures the last N seconds regardless of clock boundaries.

Configuration:
```yaml
axion:
  meta:
    rate-limit:
      requests-per-hour: 200    # Instagram's actual DM send limit per token
      window-seconds: 3600      # Rolling window duration
```

---

## WebhookEventOrchestrator — Stage 4-5 Coordinator

`WebhookEventOrchestrator` is the coordinator between the consumer (Stage 3) and the automation engine (Stage 4-5). For each `MessageDTO`, it:

1. Marks event status = `PROCESSING`
2. Resolves and decrypts the access token
3. Calls `automationEngine.evaluate(tenantId, message)`
4. Processes the `List<RuleExecutionResult>`
5. Marks event status = `PROCESSED` or `FAILED`

```java
@Service
@RequiredArgsConstructor
public class WebhookEventOrchestrator {

    private final AutomationEngine            automationEngine;
    private final InstagramOAuthTokenRepository tokenRepository;
    private final TokenEncryptionService      encryptionService;
    private final WebhookEventStatusUpdater   statusUpdater;

    public void process(UUID tenantId, String igAccountId, MessageDTO message, String eventId) {
        try {
            statusUpdater.markProcessing(eventId);

            List<RuleExecutionResult> results = automationEngine.evaluate(tenantId, message);

            long sent    = results.stream().filter(r -> r.status() == SENT).count();
            long skipped = results.stream().filter(r -> r.status() == SKIPPED).count();
            long failed  = results.stream().filter(r -> r.status() == FAILED).count();

            log.info("[orchestrator] mid={} sent={} skipped={} failed={}",
                    message.messageId(), sent, skipped, failed);

            statusUpdater.markProcessed(eventId);

        } catch (Exception ex) {
            log.error("[orchestrator] Processing failed for eventId={}", eventId, ex);
            statusUpdater.markFailed(eventId, ex.getMessage());
            throw ex; // re-throw so consumer can DLQ the event
        }
    }
}
```
