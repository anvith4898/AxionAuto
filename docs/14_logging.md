# Logging & Observability

## Overview

AxionAuto implements structured, correlated logging throughout the entire pipeline using:
- **MDC (Mapped Diagnostic Context)** for request-scoped key-value enrichment
- **SLF4J + Logback** for output
- **`PipelineLoggingContext`** as an auto-closeable MDC manager
- **Micrometer** for metric counters and gauges
- **Spring Actuator** for health, info, and Prometheus exposition

---

## Log Format

Configured in `application.yml`:

```yaml
logging:
  pattern:
    console: "%d{ISO8601} %highlight(%-5level) [%thread] [traceId=%X{traceId}] [tenantId=%X{tenantId}] %logger{36} - %msg%n"
  level:
    root: INFO
    com.axion: DEBUG
    org.springframework.security: WARN
```

**Example log lines:**

```
2026-04-19T05:30:01.123Z INFO  [virtual-33] [traceId=a1b2c3] [tenantId=uuid] c.a.a.i.EventPipelineIntegration - [INGEST] Received webhook event eventId=mid.001
2026-04-19T05:30:01.145Z INFO  [virtual-33] [traceId=a1b2c3] [tenantId=uuid] c.a.a.s.WebhookEventProducer - [producer] Enqueued eventId=mid.001 to stream=instagram-webhooks recordId=1713000000000-0
2026-04-19T05:30:01.200Z INFO  [virtual-44] [traceId=a1b2c3] [tenantId=uuid] c.a.a.i.WebhookStreamConsumer - [consumer] Processing eventId=mid.001
2026-04-19T05:30:01.220Z INFO  [virtual-44] [traceId=a1b2c3] [tenantId=uuid] c.a.a.a.AutomationEngine - [engine] Evaluated 2 rules in 18ms [sender=10158001, mid=mid.001]
```

Every log line from Stage 1 through Stage 5 carries the same `traceId`, enabling end-to-end correlation in Grafana/Loki.

---

## PipelineLoggingContext

`PipelineLoggingContext` is an `AutoCloseable` wrapper around MDC. Each pipeline stage opens a context, logs within it, and closes it — ensuring MDC keys don't leak between threads.

```java
public record PipelineLoggingContext(Map<String, String> keys)
        implements AutoCloseable {

    // MDC key constants
    public static final String STAGE         = "stage";
    public static final String TRACE_ID      = "traceId";
    public static final String TENANT_ID     = "tenantId";
    public static final String IG_ACCOUNT_ID = "igAccountId";
    public static final String SENDER_ID     = "senderId";
    public static final String MESSAGE_ID    = "messageId";
```

### Factory Methods

| Method | Used By | Keys Set |
|---|---|---|
| `initTrace(eventId)` | `EventPipelineIntegrationService` at ingestion start | `traceId` = `UUID.randomUUID()` (scoped to this event lifecycle) |
| `forIngest(eventId)` | Ingestion stage (INGEST/QUEUE) | `stage`, `traceId`, `eventId` |
| `forConsume(eventId)` | `WebhookStreamConsumer` | `stage=CONSUME`, `traceId`, `eventId` |
| `of(stage, tenantId, igAccountId, senderId, messageId)` | ORCHESTRATE, DISPATCH stages | All 6 keys including inherited `traceId` from MDC |

### traceId Propagation

`of()` inherits `traceId` from the current MDC before constructing the child context:

```java
public static PipelineLoggingContext of(String stage, UUID tenantId, ...) {
    String inheritedTraceId = MDC.get(TRACE_ID);  // inherit from enclosing context
    return new PipelineLoggingContext(Map.of(
        STAGE,    stage,
        TRACE_ID, inheritedTraceId != null ? inheritedTraceId : "no-trace",
        ...
    ));
}
```

This ensures that even though ORCHESTRATE/DISPATCH run in a different logical scope from INGEST, the `traceId` is continuous across all 5 stages of the pipeline for a single event.

### Usage Pattern

```java
// In WebhookStreamConsumer:
try (PipelineLoggingContext ctx = PipelineLoggingContext.forConsume(eventId)) {
    log.info("[consumer] Processing eventId={}", eventId);  // traceId in MDC
    // ... process ...
} // MDC keys removed automatically on close
```

### `close()` Implementation

```java
@Override
public void close() {
    keys.keySet().forEach(MDC::remove);  // clean up only keys we set
}
```

This selectively removes only the keys added by this context, without affecting any parent MDC state.

---

## Log Points by Stage

### Stage 1: INGEST

| Level | Logger | Message Pattern |
|---|---|---|
| `WARN` | WebhookController | `[ingest] Rejected webhook — invalid signature` |
| `INFO` | EventPipelineIntegration | `[INGEST] Received webhook event eventId={...}` |
| `INFO` | EventPipelineIntegration | `[INGEST] Duplicate eventId detected: {eventId}` |
| `ERROR` | EventPipelineIntegration | `[INGEST] Queue failure for eventId={...}` |

### Stage 2: QUEUE

| Level | Logger | Message Pattern |
|---|---|---|
| `INFO` | WebhookEventProducer | `[producer] Enqueued eventId={} to stream={} recordId={}` |
| `ERROR` | WebhookEventProducer | `[producer] CRITICAL: failed to enqueue eventId={} — rethrowing for HTTP 500` |

### Stage 3: CONSUME + NORMALIZE

| Level | Logger | Message Pattern |
|---|---|---|
| `INFO` | WebhookStreamConsumer | Processing started |
| `WARN` | WebhookStreamConsumer | `[consumer] Parked eventId={} to DLQ: errorCode={}` |
| `WARN` | WebhookSignatureValidator | `Invalid or missing X-Hub-Signature-256 header` |
| `DEBUG` | MessageDTOValidator | `MessageDTO validated [senderId={}, type={}, mid={}]` |
| `WARN` | MessageDTOValidator | DLQ-bound validation failures with error codes |

### Stage 4: ORCHESTRATE + DECIDE

| Level | Logger | Message Pattern |
|---|---|---|
| `DEBUG` | AutomationEngine | `[engine] contact={}, firstInteraction={}` |
| `DEBUG` | AutomationEngine | `[engine] No matching rules for message {} from sender {}` |
| `DEBUG` | AutomationEngine | `[engine] FIRST_MATCH: stopping after rule '{}' fired` |
| `DEBUG` | AutomationEngine | `[engine] Rule '{}' suppressed by cooldown for sender {}` |
| `INFO` | AutomationEngine | `[engine] Evaluated {} rules in {}ms [sender={}, mid={}]` |
| `INFO` | RuleCache | `Rule cache miss — loading rules for account {}` |
| `INFO` | RuleCache | `Loaded {} rules into cache for account {}` |

### Stage 5: DISPATCH + SEND

| Level | Logger | Message Pattern |
|---|---|---|
| `INFO` | InstagramMessageSender | `[sender] Sending reply to senderId={} from igAccountId={}` |
| `ERROR` | InstagramMessageSender | `[sender] Circuit breaker OPEN for meta-graph-api` |
| `WARN` | GraphApiRateLimiter | `[rate-limiter] Rate limit exceeded for igAccountId={}` |
| `DEBUG` | GraphApiRateLimiter | `[rate-limiter] igAccountId={} usage={}/{} in rolling {}s window` |

### DLQ Operations

| Level | Logger | Message Pattern |
|---|---|---|
| `INFO` | DlqReplayService | `[dlq:PEEK] Reading up to {} records from DLQ stream` |
| `INFO` | DlqReplayService | `[dlq:REPLAY] Re-added eventId={} with real payload ({} bytes) to main stream` |
| `WARN` | DlqReplayService | `[dlq:REPLAY] No payload found in DB for eventId={} — skipping` |
| `WARN` | DlqReplayService | `[dlq:PURGE] Purging all records from DLQ stream` |
| `WARN` | DlqReplayService | `[dlq:GAUGE] Failed to read DLQ stream length — gauge stale` |

---

## Micrometer Metrics

### Pipeline Counters

Registered in `EventPipelineIntegrationService`:

| Metric Name | Type | Description |
|---|---|---|
| `pipeline.events.ingested` | Counter | Total unique events received |
| `pipeline.events.rejected` | Counter | Events rejected for invalid HMAC |
| `pipeline.events.duplicate` | Counter | Events that were already processed (idempotency) |
| `pipeline.events.enqueued` | Counter | Events successfully written to Redis Stream |
| `pipeline.events.enqueue_failed` | Counter | Redis XADD failures |
| `pipeline.messages.processed` | Counter | Full pipeline successes |
| `pipeline.messages.failed` | Counter | Pipeline-level failures |

### Token Refresh Counters

Registered in `TokenRefreshScheduler`:

| Metric Name | Type | Description |
|---|---|---|
| `pipeline.token.refresh.success` | Counter | Tokens successfully refreshed |
| `pipeline.token.refresh.failed` | Counter | Token refresh failures |

### DLQ Gauge

Registered in `DlqReplayService`:

| Metric Name | Type | Description |
|---|---|---|
| `pipeline.dlq.depth` | Gauge | Current number of entries in the DLQ stream (refreshed every 30s) |

---

## Spring Actuator Endpoints

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized
  metrics:
    tags:
      application: axion-auth
  tracing:
    sampling:
      probability: 1.0   # 100% trace sampling rate
```

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | System health: DB, Redis, Resilience4j circuit breakers |
| `GET /actuator/info` | Application version and build info |
| `GET /actuator/metrics` | All registered Micrometer metrics |
| `GET /actuator/metrics/pipeline.dlq.depth` | Current DLQ depth |
| `GET /actuator/prometheus` | Prometheus scrape endpoint (all metrics in `text/plain` format) |

---

## Alerting Recommendations

| Alert | Condition | Severity |
|---|---|---|
| DLQ depth growing | `pipeline.dlq.depth > 100` | Warning |
| DLQ depth critical | `pipeline.dlq.depth > 1000` | Critical |
| Token refresh failures | `pipeline.token.refresh.failed > 0` in last 24h | Warning |
| Message failure rate | `pipeline.messages.failed / pipeline.messages.processed > 0.05` | Warning |
| Circuit breaker open | Actuator health → `meta-graph-api` != `CLOSED` | Critical |
| Enqueue failures | `pipeline.events.enqueue_failed > 0` in last 5 minutes | Critical |

---

## Distributed Tracing

`spring.management.tracing.sampling.probability: 1.0` enables 100% trace sampling. When Micrometer Tracing + a compatible exporter (Zipkin, Jaeger, Tempo) is configured, `traceId` and `spanId` are automatically added to MDC — making the explicit `PipelineLoggingContext.traceId` complementary to, not duplicating, the distributed trace.

For production, lower sampling to `0.1` (10%) to reduce storage costs on high-volume deployments.
