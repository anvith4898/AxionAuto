# Message Processing — Normalization & Consumer Pipeline

## Overview

After a webhook event is written to the Redis Stream, a background consumer group reads it, normalizes the raw JSON into a typed `MessageDTO`, validates it, resolves the owning tenant, and hands it off to the orchestrator for rule evaluation.

This constitutes **Stage 3** of the pipeline.

---

## WebhookStreamConsumer — Redis Stream Consumer (Stage 3)

`WebhookStreamConsumer` is a Spring `StreamListener` annotated bean registered with the `axion-processors` consumer group on the `instagram-webhooks` stream.

### Consumer Group Configuration (`RedisStreamConfig`)

```java
@Bean
public RedisMessageListenerContainer streamListenerContainer(...) {
    // Creates consumer group "axion-processors" on stream "instagram-webhooks"
    // XGROUP CREATE instagram-webhooks axion-processors 0 MKSTREAM
}
```

- Consumer group: `axion-processors`
- Consumer name: `axion-consumer-1` (multiple replicas can each have a unique name for parallel processing)
- `MKSTREAM`: creates the stream if it doesn't exist on startup

### Message Processing Flow

```java
@StreamListener
public void onMessage(MapRecord<String, Object, Object> record) {
    String eventId = getString(record.getValue(), "event_id");
    String payload  = getString(record.getValue(), "payload");

    try (PipelineLoggingContext ctx = PipelineLoggingContext.forConsume(eventId)) {

        // 1. Resolve IG account ID from payload
        String igAccountId = extractIgAccountId(payload);

        // 2. Resolve tenant via indexed DB lookup
        UUID tenantId = resolveTenant(igAccountId);  // O(1) indexed query

        // 3. Normalize raw payload → List<MessageDTO>
        List<MessageDTO> messages = normalizationService.normalize(payload, tenantId, igAccountId);

        // 4. Pass to orchestrator for each message
        for (MessageDTO msg : messages) {
            orchestrator.process(tenantId, igAccountId, msg, eventId);
        }

        // 5. Acknowledge to consumer group (XACK)
        ackRecord(record);

    } catch (Exception e) {
        parkToDlq(eventId, payload, "CONSUMER_ERROR", e.getMessage());
    }
}
```

### Tenant Resolution

```java
private UUID resolveTenant(String igAccountId) {
    return tokenRepository
        .findFirstByInstagramAccountIdAndStatus(igAccountId, TokenStatus.ACTIVE)
        .map(InstagramOAuthToken::getTenantId)
        .orElse(null);
}
```

Uses the indexed query `findFirstByInstagramAccountIdAndStatus()` on `(instagram_account_id, status)` — O(1) single-row lookup. The previous `findAll()` pattern was a full-table scan and has been eliminated.

If `null` is returned (no tenant found) → event is parked to DLQ with `NO_TENANT_TOKEN`.

---

## WebhookNormalizationService

The normalization service converts a raw webhook JSON payload into a list of `MessageDTO` objects. One webhook delivery can contain multiple entries, each with multiple messaging objects.

```java
public List<MessageDTO> normalize(String rawPayload, UUID tenantId, String igAccountId) {
    List<MessageDTO> results = new ArrayList<>();

    // 1. Parse the raw JSON
    List<MessageDTO> parsed = parser.parse(rawPayload, tenantId, igAccountId);

    for (MessageDTO dto : parsed) {
        // 2. Validate each DTO
        ValidationResult result = validator.validate(dto);

        if (!result.valid()) {
            log.warn("[normalize] Invalid DTO [{}]: {}", result.errorCode(), result.errorMessage());
            // Park invalid messages to DLQ (non-blocking)
            dlqProducer.park(dto.rawEventId(), result.errorCode(), result.errorMessage());
            continue;
        }

        results.add(dto);
    }

    return results; // may be empty if all messages failed validation
}
```

---

## WebhookPayloadParser

Converts the raw Meta webhook JSON into typed `MessageDTO` objects:

```json
// Input (raw webhook body):
{
  "object": "instagram",
  "entry": [
    {
      "id": "17841400001", "time": 1712678400,
      "messaging": [
        {
          "sender":    { "id": "10158001" },
          "recipient": { "id": "17841400001" },
          "timestamp": 1712678400001,
          "message": { "mid": "mid.001", "text": "Hello" }
        }
      ]
    }
  ]
}
```

For each `messaging` entry, the parser builds a `MessageDTO`:
```java
MessageDTO {
    String rawEventId;      // original event_id from stream record
    String messageId;       // messaging[].message.mid
    String senderId;        // messaging[].sender.id (IGSID)
    String recipientId;     // messaging[].recipient.id
    String igAccountId;     // entry[].id (IG Business Account)
    UUID   tenantId;        // resolved by consumer
    Instant timestamp;      // from messaging[].timestamp (ms → Instant)
    MessageType messageType; // TEXT | IMAGE | AUDIO | VIDEO | STORY_MENTION | UNKNOWN
    String messageText;     // from message.text (null for media; empty string by default)
}
```

**Message type classification:**

| Condition | MessageType |
|---|---|
| `message.text` is present | `TEXT` |
| `message.attachments[].type == "image"` | `IMAGE` |
| `message.attachments[].type == "audio"` | `AUDIO` |
| `message.attachments[].type == "video"` | `VIDEO` |
| `message.story_mention` present | `STORY_MENTION` |
| None of the above | `UNKNOWN` |

---

## MessageDTOValidator

Applies 4 sequential validation rules. All rules must pass. A failed DTO is parked to DLQ — the validator itself never writes to the DLQ (SRP).

| Rule | Check | Error Code |
|---|---|---|
| **1. Sender ID** | Non-blank, numeric, ≥ 6 digits | `MISSING_SENDER_ID` / `INVALID_SENDER_ID` |
| **2. Timestamp** | Not more than 30s in the future; not older than 24 hours | `TIMESTAMP_IN_FUTURE` / `TIMESTAMP_TOO_OLD` |
| **3. Message type** | Not `UNKNOWN` | `UNKNOWN_MESSAGE_TYPE` |
| **4. Text length** | `messageText.length() ≤ 2048` | `TEXT_TOO_LONG` |

**Replay protection** is enforced by Rule 2 — events older than 24 hours are rejected to prevent stale or maliciously replayed webhooks from triggering automation.

Usage pattern:
```java
ValidationResult result = validator.validate(dto);
if (!result.valid()) {
    dlqProducer.park(dto.rawEventId(), result.errorCode(), result.errorMessage());
    return; // do not process
}
// safe to continue
```

`ValidationResult` is an inner record of `MessageDTOValidator`:
```java
public record ValidationResult(
    boolean valid,
    MessageDTO dto,
    String errorCode,    // null when valid
    String errorMessage  // null when valid
) {
    // Can be converted to a WebhookParseException if caller prefers exception propagation
    public WebhookParseException toException() { ... }
}
```

---

## MessageDTO — Normalized Inbound Message

Key fields:

| Field | Type | Source | Notes |
|---|---|---|---|
| `messageId` | String | `message.mid` | Meta's globally unique message ID |
| `senderId` | String | `sender.id` | Instagram-Scoped ID (IGSID) |
| `recipientId` | String | `recipient.id` | The IG Business Account's IGSID |
| `igAccountId` | String | `entry[].id` | IG Business Account ID for tenant lookup |
| `tenantId` | UUID | Resolved from token table | Added by consumer during normalization |
| `timestamp` | Instant | `timestamp` (ms) | Parsed from Meta's epoch-millisecond timestamp |
| `messageType` | MessageType | Classified by parser | Controls automation routing |
| `messageText` | String | `message.text` | Lowercased/trimmed by normalizer |
| `rawEventId` | String | Stream record field | Used for DLQ references |

Helper methods:
```java
public boolean hasText()   { return !messageText.isBlank(); }
public boolean isTextDm()  { return messageType == MessageType.TEXT; }
```

---

## Consumer Acknowledgment & Error Recovery

The consumer uses `XACK` to acknowledge a record only **after** successful processing:

```
XACK instagram-webhooks axion-processors {recordId}
```

If processing fails before `XACK`, the record remains in the **Pending Entry List (PEL)** of the consumer group. A separate recovery process (future) can use `XAUTOCLAIM` to reclaim and retry stale PEL entries after a timeout.

Currently, unrecoverable failures are handled by `parkToDlq()`:
```java
private void parkToDlq(String eventId, String payload, String errorCode, String errorMessage) {
    redisTemplate.opsForStream().add(
        StreamRecords.newRecord()
            .ofMap(Map.of(
                "event_id",      eventId,
                "error_code",    errorCode,
                "error_message", errorMessage
            ))
            .withStreamKey("instagram-webhooks-dlq")
    );
    statusUpdater.markDlq(eventId, errorCode); // DB audit trail
}
```
