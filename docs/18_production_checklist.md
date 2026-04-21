# Production Readiness Checklist

## Quick Reference

| Category | Items | Status |
|---|---|---|
| Security | 10 | 8 ✅ 2 ⚠️ |
| Reliability | 9 | 9 ✅ |
| Performance | 6 | 6 ✅ |
| Observability | 7 | 7 ✅ |
| Operations | 8 | 5 ✅ 3 ⏳ |
| Database | 6 | 6 ✅ |
| **Total** | **46** | **41 ✅ 5 ⚠️/⏳** |

---

## Security

| # | Control | Status | Where Implemented |
|---|---|---|---|
| S1 | HMAC-SHA256 webhook validation (timing-safe) | ✅ | `WebhookSignatureValidator.isValidSignature()` — `MessageDigest.isEqual()` |
| S2 | OAuth CSRF state token (Redis, TTL, single-use) | ✅ | `InstagramOAuthService` — `GETDEL oauth:state:{token}` |
| S3 | AES-256-GCM token encryption at rest | ✅ | `TokenEncryptionService` — per-value IV + auth tag |
| S4 | `@ToString.Exclude` on encrypted token fields | ✅ | `InstagramOAuthToken` entity — prevents token logging |
| S5 | PostgreSQL RLS on every table | ✅ | V1–V4 Flyway migrations |
| S6 | CSRF protection disabled (correct for stateless REST) | ✅ | `SecurityConfig` |
| S7 | Environment-variable secrets (no hardcoding) | ✅ | All credentials via `@ConfigurationProperties` |
| S8 | Replay-attack protection (24h timestamp window) | ✅ | `MessageDTOValidator` Rule 2 |
| S9 | Token encryption key rotation | ⚠️ Not Yet | Requires re-encryption pipeline (future) |
| S10 | Webhook IP allowlisting (Meta source IPs) | ⚠️ Not Yet | Configure at load balancer/WAF |

---

## Reliability

| # | Control | Status | Where Implemented |
|---|---|---|---|
| R1 | Two-layer idempotency (Redis SETNX + DB unique constraint) | ✅ | `WebhookIdempotencyService` |
| R2 | Redis Stream at-least-once delivery (consumer groups + PEL) | ✅ | `RedisStreamConfig`, `WebhookStreamConsumer` |
| R3 | Resilience4j `@Retry` — 4 attempts, exponential backoff (1s→2s→4s) | ✅ | `InstagramMessageSenderService` + `application.yml` |
| R4 | Resilience4j `@CircuitBreaker` — 50% failure rate → OPEN, 30s recovery | ✅ | `InstagramMessageSenderService` + `application.yml` |
| R5 | DLQ (Dead Letter Queue) for unrecoverable failures | ✅ | `WebhookStreamConsumer.parkToDlq()`, `DlqReplayService` |
| R6 | DLQ replay with real payload from DB (no infinite loop) | ✅ | `DlqReplayService.replayOne()` — fetches `WebhookEventEntity.payload` |
| R7 | Redis failure re-throws → HTTP 500 → Meta retries ingestion | ✅ | `WebhookEventProducer.pushToStream()` |
| R8 | `REQUIRES_NEW` transaction isolation for status updates | ✅ | `WebhookEventStatusUpdater` |
| R9 | Proactive token refresh (48h lookahead, daily at 02:00) | ✅ | `TokenRefreshScheduler` |

---

## Performance

| # | Control | Status | Where Implemented |
|---|---|---|---|
| P1 | HTTP ingest completes in < 50ms (async decoupling) | ✅ | Redis Stream separates ingest from processing |
| P2 | In-memory inverted index rule cache (O(tokens) lookup) | ✅ | `RuleCache` — `ConcurrentHashMap` + keyword index |
| P3 | O(1) tenant resolution (indexed query, not `findAll()`) | ✅ | `findFirstByInstagramAccountIdAndStatus()` on `(ig_account_id, status)` |
| P4 | Contact upsert via `ON CONFLICT DO UPDATE` (single round-trip) | ✅ | `ContactRepository.upsertContact()` |
| P5 | Cooldown check uses compound index on execution log | ✅ | `AutomationExecutionLogRepository.existsWithinCooldown()` |
| P6 | Java 21 virtual threads for all request handling | ✅ | `spring.threads.virtual.enabled: true` |

---

## Observability

| # | Control | Status | Where Implemented |
|---|---|---|---|
| O1 | MDC-enriched structured logging (`traceId`, `tenantId`, `stage`) | ✅ | `PipelineLoggingContext` |
| O2 | `traceId` propagated to all pipeline stages (INGEST→SEND) | ✅ | `PipelineLoggingContext.of()` inherits from MDC |
| O3 | Pipeline event counters (ingested, enqueued, rejected, duplicate) | ✅ | `EventPipelineIntegrationService` — Micrometer counters |
| O4 | Message processing counters (processed, failed) | ✅ | `EventPipelineIntegrationService` — Micrometer counters |
| O5 | DLQ depth gauge (refreshed every 30s) | ✅ | `DlqReplayService.refreshDlqDepth()` — Micrometer gauge |
| O6 | Token refresh success/failure counters | ✅ | `TokenRefreshScheduler` — Micrometer counters |
| O7 | Spring Actuator endpoints: `/health`, `/prometheus`, `/metrics` | ✅ | `application.yml` — `management.endpoints.web` |

---

## Operations

| # | Control | Status | Where Implemented |
|---|---|---|---|
| OP1 | DLQ inspect endpoint (`GET /dlq`) | ✅ | `WebhookController` → `DlqReplayService.peek()` |
| OP2 | DLQ replay endpoint (`POST /dlq/replay`) | ✅ | `WebhookController` → `DlqReplayService.replay()` |
| OP3 | DLQ purge capability | ✅ | `DlqReplayService.purge()` |
| OP4 | Pipeline stats endpoint (`GET /stats`) | ✅ | `WebhookController` — returns `AtomicLong` counters |
| OP5 | Circuit breaker health visible in Actuator | ✅ | `resilience4j.circuitbreaker.register-health-indicator: true` |
| OP6 | Prometheus/Grafana dashboards | ⏳ Not Yet | Prometheus configured; dashboards not built |
| OP7 | Alerting rules (DLQ depth, CB state, token failures) | ⏳ Not Yet | Metric names documented in `14_logging.md` |
| OP8 | `XAUTOCLAIM` PEL recovery for stale consumer entries | ⏳ Planned | Not yet implemented; documented as future enhancement |

---

## Database

| # | Control | Status | Where Implemented |
|---|---|---|---|
| DB1 | Flyway versioned migrations (V1–V4) | ✅ | `src/main/resources/db/migration/` |
| DB2 | `@UniqueConstraint` on `event_id` (second idempotency layer) | ✅ | V2 migration + `WebhookEventEntity` |
| DB3 | `@UniqueConstraint` on `(tenant_id, ig_account_id)` for tokens | ✅ | V1 migration + `InstagramOAuthToken` entity |
| DB4 | Compound indexes on all hot-path queries | ✅ | V1–V4 migrations — documented in `09_database.md` |
| DB5 | Row-Level Security on every table | ✅ | V1–V4 migrations — `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` |
| DB6 | `ddl-auto: validate` (schema managed by Flyway only) | ✅ | `application.yml` |

---

## Pre-Launch Verification Checklist (Operator Steps)

### Environment Configuration
- [ ] `TOKEN_ENCRYPTION_KEY` — 32-byte Base64 random, stored in Secrets Manager
- [ ] `META_APP_SECRET` — from Meta App dashboard
- [ ] `META_OAUTH_REDIRECT_URI` — HTTPS URL matching Meta App configuration
- [ ] `META_WEBHOOK_VERIFY_TOKEN` — matches what's registered in Meta Webhook dashboard
- [ ] All DB and Redis credentials set and accessible

### Database Health
- [ ] `SELECT * FROM flyway_schema_history ORDER BY installed_rank` — all migrations `SUCCESS`
- [ ] `SELECT * FROM instagram_oauth_tokens LIMIT 1` — RLS not blocking (check `app.current_tenant` setting)
- [ ] Run `EXPLAIN` on the `existsWithinCooldown` query to verify index usage
- [ ] Verify `idx_ig_tokens_ig_account_status` index exists

### Application Health
- [ ] `GET /actuator/health` → `{ "status": "UP" }`
- [ ] `GET /actuator/health` → DB and Redis components both up
- [ ] `GET /actuator/prometheus` → metrics endpoint responding
- [ ] Circuit breaker state = `CLOSED`

### Webhook Verification
- [ ] Meta App dashboard: webhook URL subscribed and verified
- [ ] Test webhook delivery via Meta App dashboard → `GET /actuator/metrics/pipeline.events.ingested` increments

### OAuth Flow
- [ ] End-to-end OAuth connection: authorize → callback → token stored
- [ ] `SELECT status FROM instagram_oauth_tokens` → `ACTIVE`
- [ ] Proactive refresh scheduler will run within 24h (check logs after 02:00 AM)

### DM Processing
- [ ] Send a test DM to connected Business Account
- [ ] Verify `GET /stats` → `eventsIngested += 1`, `messagesProcessed += 1`
- [ ] Verify `AutomationExecutionLog` has a `SENT` entry
- [ ] Verify the bot reply appeared in the Instagram inbox

### Rate Limiting
- [ ] Send 10 rapid test DMs → verify `GraphApiRateLimiter` DEBUG log shows correct counts
- [ ] Confirm the test environment rate limit is set appropriately (not 200/hour in dev)

### DLQ Operations
- [ ] `GET /dlq` returns empty or existing entries
- [ ] Simulate a failed event → verify it appears in DLQ
- [ ] `POST /dlq/replay?limit=1` → event re-processed → DLQ emptied

---

## Known Limitations / Future Enhancements

| Limitation | Impact | Proposed Solution |
|---|---|---|
| `RuleCache` invalidation is local to each replica | After a rule update, replicas won't pick up the change until restart | Redis pub/sub invalidation signal |
| Rate limiter operations are not atomic Lua | Very slight overcount possible under high concurrency | Lua script for atomic check-and-increment |
| `XAUTOCLAIM` PEL recovery not implemented | Messages in failed consumer's PEL are stuck until manual claim | Scheduled `XAUTOCLAIM` job |
| Token key rotation | Re-encryption of stored tokens needed on key change | Batch re-encryption job |
| Webhook IP allowlisting | Forged requests can attempt HMAC brute-force (mitigated by timing-safe compare) | Allowlist Meta's published webhook source IPs at load balancer |
| No tenant authentication on protected routes | Rule CRUD and account management are unprotected | JWT middleware for authenticated routes |
| Sampling set to 1.0 (100% traces) | High storage cost in prod | Set to 0.1 (10%) or use head-based sampling |
