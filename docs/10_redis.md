# Redis — Usage Patterns & Design

## Overview

Redis 7+ is used for four distinct purposes in AxionAuto, each using a different data structure and keyspace convention:

| Purpose | Data Structure | Keyspace Pattern |
|---|---|---|
| OAuth state (CSRF protection) | String (JSON) | `oauth:state:{stateToken}` |
| Webhook idempotency | String (flag) | `idempotency:webhook:{eventId}` |
| Event queue (main pipeline) | Stream | `instagram-webhooks` |
| Dead Letter Queue | Stream | `instagram-webhooks-dlq` |
| Rate limiting | Sorted Set | `rate_limit:ig_account:{igAccountId}` |

---

## Connection Configuration

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      lettuce:
        pool:
          max-active: 16   # max concurrent connections
          max-idle: 8
          min-idle: 2
```

**Client**: Lettuce (async, thread-safe, connection pooling)
**Template**: `StringRedisTemplate` for most operations (keys and values are UTF-8 strings; no Java serialization overhead)

Two `RedisTemplate` beans are configured to avoid serialization conflicts:
- `StringRedisTemplate` (default) — used for idempotency keys, rate limiter, stream operations
- `RedisTemplate<String, String> oauthStateRedisTemplate` (qualifier: `"oauthStateRedisTemplate"`) — isolated for OAuth state management

---

## 1. OAuth State (CSRF Protection)

**Purpose:** Store the OAuth `stateToken` → `OAuthStatePayload` mapping during the authorize → callback round trip.

```
Key:   oauth:state:{stateToken}     (stateToken is a random UUID)
Type:  String
Value: JSON serialized OAuthStatePayload
TTL:   10 minutes (Duration.ofMinutes(10))
```

**Lifecycle:**
1. `SET oauth:state:{stateToken} {json} EX 600` — on authorize URL generation
2. `GETDEL oauth:state:{stateToken}` — on callback receipt (atomic get + delete = consumed once)

If the key is missing on callback (expired or never existed) → `OAuthException(INVALID_STATE)`.

**Security property:** The `GETDEL` ensures a state token can only be used once, even if the callback URL is replayed by an attacker.

---

## 2. Webhook Idempotency

**Purpose:** Detect and reject duplicate webhook deliveries before they touch the database or get enqueued.

```
Key:   idempotency:webhook:{eventId}
Type:  String ('1')
TTL:   24 hours
```

**Operation:**
```
SETNX idempotency:webhook:{eventId} "1"  EX 86400
```

- Returns `true` (key set) → new event, proceed
- Returns `false` (key existed) → duplicate, return early

**Note:** 24-hour TTL is calibrated against `MessageDTOValidator`'s 24-hour timestamp check — events older than 24 hours are also rejected at the validation layer, preventing stale events from consuming a cache slot indefinitely.

---

## 3. Event Stream — Main Pipeline

**Purpose:** Decouple the synchronous HTTP ingest from the asynchronous processing pipeline.

```
Stream key: instagram-webhooks
Consumer group: axion-processors
Consumer ID:    axion-consumer-1 (configurable per replica)
```

### Message Structure

Each stream entry is a flat map:
```
{
  "event_id": "mid.pricing.001",
  "payload":  "{...raw webhook JSON...}"
}
```

DLQ replay entries additionally include:
```
{
  "event_id": "mid.pricing.001",
  "payload":  "{...original raw JSON from DB...}",
  "replay":   "true"
}
```

### Key Commands

```
# Producer (WebhookEventProducer):
XADD instagram-webhooks * event_id {id} payload {json}

# Consumer setup (RedisStreamConfig, on startup):
XGROUP CREATE instagram-webhooks axion-processors $ MKSTREAM

# Consumer reads (WebhookStreamConsumer, polling):
XREADGROUP GROUP axion-processors axion-consumer-1 COUNT 10 BLOCK 2000 STREAMS instagram-webhooks >

# Acknowledge after successful processing:
XACK instagram-webhooks axion-processors {recordId}
```

### At-Least-Once Delivery

The `>` in `XREADGROUP` means "give me only new messages not yet delivered to any consumer in this group." If processing fails before `XACK`, the record stays in the **Pending Entry List (PEL)** and can be reclaimed:
```
# Reclaim messages pending > 5 minutes (XAUTOCLAIM — future enhancement):
XAUTOCLAIM instagram-webhooks axion-processors axion-consumer-1 300000 0-0
```

### Horizontal Scaling

Multiple instances of the service can each consume from the same consumer group without duplicating work:
- Pod 1: consumer name `axion-consumer-1`
- Pod 2: consumer name `axion-consumer-2`
- Redis distributes records across consumers automatically

---

## 4. Dead Letter Queue (DLQ)

**Purpose:** Park unrecoverable pipeline failures for operator inspection and manual replay.

```
Stream key: instagram-webhooks-dlq
```

### DLQ Entry Structure

```
{
  "event_id":      "mid.pricing.001",
  "error_code":    "NO_TENANT_TOKEN",
  "error_message": "No active token found for igAccountId=17841400"
}
```

### Managed via DlqReplayService

```java
// Peek (read without consuming)
List<MapRecord<...>> records = redisTemplate.opsForStream()
    .range(DLQ_STREAM_KEY, Range.unbounded());

// Re-add to main stream (with real payload from DB)
XADD instagram-webhooks * event_id {id} payload {realPayload} replay true

// Remove from DLQ after successful re-add
XDEL instagram-webhooks-dlq {recordId}

// Purge all entries (irreversible)
XTRIM instagram-webhooks-dlq MAXLEN 0
```

### DLQ Depth Gauge

`DlqReplayService` registers a Micrometer gauge `pipeline.dlq.depth` that checks stream length every 30 seconds:

```java
@Scheduled(fixedDelay = 30_000)
public void refreshDlqDepth() {
    Long len = redisTemplate.opsForStream().size(DLQ_STREAM_KEY);
    dlqDepth.set(len != null ? len : 0L);
}
```

This feeds into Prometheus/Grafana for alerting when the DLQ grows beyond acceptable thresholds.

---

## 5. Rate Limiting — Sorted Set Sliding Window

**Purpose:** Enforce Instagram's 200 DM-sends/hour per access token limit before calling the Graph API.

```
Key:    rate_limit:ig_account:{instagramAccountId}
Type:   Sorted Set
Score:  epoch milliseconds
Member: epoch milliseconds (same as score; ensures uniqueness per call)
TTL:    windowSeconds + 10 = 3610 seconds
```

### Sliding Window Algorithm

```
Per API call:
 1. ZREMRANGEBYSCORE key 0 (nowMs - 3600000)   → evict old entries
 2. ZADD key nowMs nowMs                        → record this call
 3. EXPIRE key 3610                             → auto-cleanup
 4. ZCARD key                                   → count in-window calls
 5. if count > 200: ZREM key nowMs; throw RateLimitExceededException
```

**Atomicity note:** These operations are not in a Lua script. In a high-concurrency scenario, a race between ZADD and ZCARD is possible. The practical effect is a slight overcount (a few extra calls allowed). For production enforcement of strict limits, the commands should be wrapped in a Lua script:

```lua
-- Future enhancement: atomic rate limit check
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local count = redis.call('ZADD', key, now, now)
redis.call('EXPIRE', key, math.ceil(window/1000) + 10)
local total = redis.call('ZCARD', key)
if total > limit then
  redis.call('ZREM', key, now)
  return 0  -- rejected
end
return 1  -- accepted
```

---

## Redis Key Summary

| Key Pattern | Type | TTL | Used By |
|---|---|---|---|
| `oauth:state:{token}` | String | 10 min | `InstagramOAuthService` |
| `idempotency:webhook:{eventId}` | String | 24 h | `WebhookIdempotencyService` |
| `instagram-webhooks` | Stream | ∞ (trimmed manually) | `WebhookEventProducer`, `WebhookStreamConsumer` |
| `instagram-webhooks-dlq` | Stream | ∞ (operator purged) | `WebhookStreamConsumer`, `DlqReplayService` |
| `rate_limit:ig_account:{id}` | Sorted Set | ~1 h | `GraphApiRateLimiter` |
