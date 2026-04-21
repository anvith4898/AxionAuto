# Webhook Ingestion

## Overview

Instagram Business Accounts can subscribe to Meta's Webhook API to receive real-time notifications when users send DMs, mention stories, or interact in other ways. AxionAuto exposes a webhook endpoint that Meta calls for every inbound interaction.

The ingestion path is **strictly synchronous and must complete within 20 seconds** (Meta's timeout). In practice it completes in < 50ms because all heavy processing is deferred to the async Redis Stream pipeline.

---

## Webhook Verification (One-Time Setup)

When a developer subscribes a webhook in the Meta App dashboard, Meta sends a verification challenge:

```
GET /api/v1/webhooks/instagram
  ?hub.mode=subscribe
  &hub.verify_token={META_WEBHOOK_VERIFY_TOKEN}
  &hub.challenge={random_string}
```

The controller responds:
```java
if ("subscribe".equals(mode) && properties.webhookVerifyToken().equals(verifyToken)) {
    return ResponseEntity.ok(challenge);  // Echo back the challenge
}
return ResponseEntity.status(403).build();
```

`META_WEBHOOK_VERIFY_TOKEN` is an arbitrary secret set in both Meta's dashboard and the application's environment variable.

---

## Inbound Webhook — 5-Stage Ingest Flow

```
META POST
  │
  ▼
WebhookController.receiveEvents()
  │  extracts eventId from payload
  ▼
EventPipelineIntegrationService.ingest(rawPayload, sigHeader, eventId)
  │
  ├─[Stage 1a]─► WebhookSignatureValidator.isValidSignature()
  │                   FAIL → IngestResult.REJECTED → HTTP 403
  │
  ├─[Stage 1b]─► WebhookIdempotencyService.processAndCheckIdempotency()
  │                   DUPLICATE → IngestResult.DUPLICATE → HTTP 200 "DUPLICATE"
  │
  └─[Stage 2]──► WebhookEventProducer.pushToStream()
                     FAIL → IngestResult.QUEUE_FAILED → HTTP 500 (Meta retries)
                     OK   → IngestResult.ACCEPTED → HTTP 200 "EVENT_RECEIVED"
```

---

## Stage 1a: HMAC-SHA256 Signature Validation

Meta signs every webhook POST with an HMAC-SHA256 of the raw body using the **App Secret** as the key, and includes it in:
```
X-Hub-Signature-256: sha256={hex-encoded-hmac}
```

`WebhookSignatureValidator`:
1. Strips the `sha256=` prefix.
2. Recomputes `HMAC-SHA256(rawBody, META_APP_SECRET)`.
3. Compares using `MessageDigest.isEqual()` — **timing-safe**, constant-time comparison to prevent timing oracle attacks.

If the header is missing or the HMAC doesn't match → returns `false` → pipeline returns `IngestResult.REJECTED` → HTTP 403.

---

## Stage 1b: Idempotency Guard

Meta guarantees **at-least-once** delivery, meaning the same event may be delivered multiple times (network retries, Meta-side re-delivery). The idempotency guard ensures we process each unique event exactly once.

**Event ID extraction** (performed by `WebhookController`):
```
Priority 1: messaging[0].message.mid     (e.g., "mid.pricing.001")
Priority 2: messaging[0].timestamp       (e.g., "messaging-1712678400001")
Priority 3: entry[0].id + entry[0].time  (e.g., "17841400-1712678400")
Priority 4: hash(rawPayload)             (fallback for unknown shapes)
```

**Two-layer deduplication:**

```
Layer 1: Redis SETNX
  SETNX idempotency:webhook:{eventId} 1  EX 86400
  → Returns 1 (new) or 0 (exists)

Layer 2: DB INSERT with UNIQUE constraint on event_id
  → catches race condition between concurrent pods that both passed Layer 1
  → DataIntegrityViolationException → treated as duplicate
```

---

## Stage 2: Redis Stream XADD

`WebhookEventProducer.pushToStream()` writes the event to a Redis Stream:

```
XADD instagram-webhooks * event_id {eventId} payload {rawJsonPayload}
```

**Critical design decision:** If this XADD fails (Redis unavailable), the method **re-throws** the exception. The caller catches it, returns `IngestResult.QUEUE_FAILED` → HTTP 500 → Meta retries delivery. The event is already stored in DB as `RECEIVED`, so on retry, the idempotency guard allows it through to be re-queued.

This prevents silent event loss — the previous pattern of swallowing the exception would leave events permanently stuck in `status=RECEIVED`.

---

## WebhookController Endpoints

```
POST /api/v1/webhooks/instagram
  Header: X-Hub-Signature-256: sha256={hmac}
  Body:   {Instagram webhook JSON payload}
  → 200 "EVENT_RECEIVED"   : accepted and queued
  → 200 "DUPLICATE"        : already processed (idempotent response to Meta)
  → 403                    : invalid HMAC signature
  → 500 "QUEUE_ERROR"      : Redis unavailable; Meta should retry

GET  /api/v1/webhooks/instagram
  Query: hub.mode, hub.verify_token, hub.challenge
  → 200 {challenge}        : Meta webhook verification
  → 403                    : invalid verify token

GET  /api/v1/webhooks/instagram/stats
  → 200 {"ingested":142, "enqueued":139, "rejected":3, "duplicate":4, ...}

GET  /api/v1/webhooks/instagram/dlq?limit=20
  → 200 [{streamRecordId, eventId, errorCode, errorMessage}]

POST /api/v1/webhooks/instagram/dlq/replay?limit=10
  → 200 {"replayed":8, "failed":2, "note":null}
```

---

## Webhook Payload Shape

Meta sends a JSON payload structured as:

```json
{
  "object": "instagram",
  "entry": [
    {
      "id": "17841400000000001",
      "time": 1712678400,
      "messaging": [
        {
          "sender":    { "id": "10158000000000001" },
          "recipient": { "id": "17841400000000001" },
          "timestamp": 1712678400001,
          "message": {
            "mid":  "mid.pricing.001",
            "text": "What are your pricing plans?"
          }
        }
      ]
    }
  ]
}
```

- `entry[].id` is the **Instagram Business Account ID** — used for tenant resolution.
- `messaging[].sender.id` is the user's **Instagram-Scoped ID (IGSID)**.
- `messaging[].message.mid` is the globally unique **Message ID** — used as the idempotency key.

---

## `WebhookEventEntity` — DB Record

```java
@Entity
@Table(name = "instagram_webhook_events")
public class WebhookEventEntity {
    UUID   id;
    String eventId;    // idempotency key (unique constraint)
    String payload;    // raw JSON (used by DLQ replay to recover original payload)
    String status;     // RECEIVED | PROCESSING | PROCESSED | FAILED | DLQ
    UUID   tenantId;   // added in V4 migration
    String igAccountId;
    Instant createdAt;
    Instant updatedAt;
}
```

The `payload` column is critical for DLQ replay — it contains the original raw JSON that the consumer needs to re-process a failed event.

---

## `WebhookEventStatusUpdater` — Lifecycle Tracking

Maintains an audit trail of event processing status:

```java
@Service
public class WebhookEventStatusUpdater {

    @Transactional(propagation = REQUIRES_NEW)  // ← independent transaction
    public void markProcessing(String eventId) { updateStatus(eventId, "PROCESSING"); }

    @Transactional(propagation = REQUIRES_NEW)
    public void markProcessed(String eventId)  { updateStatus(eventId, "PROCESSED"); }

    @Transactional(propagation = REQUIRES_NEW)
    public void markFailed(String eventId, String reason) { ... }

    @Transactional(propagation = REQUIRES_NEW)
    public void markDlq(String eventId, String reason)    { ... }
}
```

`REQUIRES_NEW` ensures status updates are committed immediately and independently. If the main processing transaction rolls back (e.g., rule execution fails), the `markFailed` call still commits — preserving the audit trail.

---

## Dead Letter Queue (DLQ) Strategy

Events are parked to the `instagram-webhooks-dlq` Redis Stream when:

| Condition | Error Code | Who parks |
|---|---|---|
| JSON deserialization fails | `DESERIALIZATION_ERROR` | `WebhookStreamConsumer` |
| No ACTIVE token for IG account | `NO_TENANT_TOKEN` | `WebhookStreamConsumer` |
| Normalization returns empty list | `EMPTY_NORMALIZATION` | `WebhookStreamConsumer` |
| Validation rule violated | `MISSING_SENDER_ID`, etc. | `WebhookNormalizationService` |

The `DlqReplayService` exposes operator controls:
- `peek(N)` — inspect without consuming
- `replay(N)` — re-add to main stream (fetches original payload from DB)
- `purge()` — XTRIM to 0 (irreversible)
