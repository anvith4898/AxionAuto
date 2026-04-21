# Architecture

## Package Structure

```
src/main/java/com/axion/auth/
├── AxionAuthApplication.java          # Spring Boot entry point; enables scheduling
├── automation/
│   ├── AutomationEngine.java          # Core rule evaluation logic
│   ├── RuleCache.java                 # In-memory inverted-index cache per account
│   ├── RuleExecutionResult.java       # Value record: ruleId, name, status, note
│   └── RuleReplyDispatcher.java       # Bridges engine → message sender
├── config/
│   ├── MetaOAuthProperties.java       # @ConfigurationProperties: Meta app credentials
│   ├── OAuthInfrastructureConfig.java # RestClient, Redis template beans
│   ├── SecurityConfig.java            # Spring Security filter chain
│   └── TokenEncryptionProperties.java # AES key from environment
├── controller/
│   ├── InstagramOAuthController.java  # GET /oauth/authorize, GET /oauth/callback
│   └── WebhookController.java         # POST /webhooks/instagram, stats, DLQ admin
├── domain/
│   ├── dto/                           # Request/response value types
│   │   ├── InstagramAccountResponse.java
│   │   ├── MessageSendRequest.java
│   │   ├── MessageSendResponse.java
│   │   ├── OAuthConnectionResult.java
│   │   ├── OAuthStatePayload.java
│   │   ├── TokenExchangeResponse.java
│   │   └── webhook/                   # Instagram webhook payload shape
│   ├── entity/                        # JPA entities
│   │   ├── AutomationExecutionLog.java
│   │   ├── AutomationRule.java
│   │   ├── Contact.java
│   │   ├── InstagramOAuthToken.java
│   │   ├── RuleKeyword.java
│   │   └── WebhookEventEntity.java
│   ├── model/
│   │   ├── MessageDTO.java            # Normalized inbound message
│   │   └── MessageType.java           # DM | STORY_MENTION | UNKNOWN
│   └── repository/
│       └── InstagramOAuthTokenRepository.java
├── exception/                         # Typed exception hierarchy
├── integration/
│   ├── DlqReplayService.java          # DLQ peek / replay / purge + depth gauge
│   ├── EventPipelineIntegrationService.java  # Central pipeline façade
│   ├── PipelineLoggingContext.java    # MDC context (AutoCloseable)
│   ├── RedisStreamConfig.java         # Consumer group, listener container
│   ├── WebhookEventOrchestrator.java  # Stages 4-5 per-message coordinator
│   ├── WebhookEventStatusUpdater.java # REQUIRES_NEW status audit trail
│   └── WebhookStreamConsumer.java     # Redis Stream consumer (Stage 3)
├── normalization/
│   ├── MessageDTOValidator.java       # 4-rule validator with DLQ strategy
│   ├── WebhookNormalizationService.java
│   └── WebhookPayloadParser.java      # Converts raw webhook → List<MessageDTO>
├── repository/
│   ├── AutomationExecutionLogRepository.java
│   ├── AutomationRuleRepository.java
│   ├── ContactRepository.java
│   └── WebhookEventRepository.java
└── service/
    ├── GraphApiRateLimiter.java        # Redis sorted-set sliding window
    ├── InstagramMessageSenderService.java  # Resilience4j-wrapped API caller
    ├── InstagramOAuthService.java      # Full OAuth 2.0 flow orchestration
    ├── MetaGraphApiClient.java         # Low-level Graph API HTTP client
    ├── TokenEncryptionService.java     # AES-256-GCM encrypt/decrypt
    ├── TokenRefreshScheduler.java      # Daily token proactive refresh
    ├── WebhookEventProducer.java       # Redis Stream XADD
    ├── WebhookIdempotencyService.java  # Redis SETNX + DB dedup
    └── WebhookSignatureValidator.java  # HMAC-SHA256 timing-safe validation
```

---

## Component Dependency Graph

```
WebhookController
    └──► EventPipelineIntegrationService          [STAGE 1-2]
              ├──► WebhookSignatureValidator
              ├──► WebhookIdempotencyService
              │         └──► StringRedisTemplate (SETNX)
              │         └──► WebhookEventRepository
              ├──► WebhookEventProducer
              │         └──► StringRedisTemplate (XADD)
              └──► WebhookEventOrchestrator        [STAGE 4-5]
                        ├──► AutomationEngine
                        │         ├──► RuleCache
                        │         │       └──► AutomationRuleRepository
                        │         ├──► ContactRepository
                        │         ├──► AutomationExecutionLogRepository
                        │         └──► RuleReplyDispatcher
                        │                   └──► InstagramMessageSenderService
                        │                             ├──► GraphApiRateLimiter
                        │                             └──► MetaGraphApiClient
                        ├──► InstagramOAuthTokenRepository
                        ├──► TokenEncryptionService
                        └──► WebhookEventStatusUpdater
                                  └──► WebhookEventRepository

RedisStreamConfig
    └──► WebhookStreamConsumer                     [STAGE 3]
              ├──► WebhookNormalizationService
              │         └──► WebhookPayloadParser
              │         └──► MessageDTOValidator
              ├──► InstagramOAuthTokenRepository
              ├──► EventPipelineIntegrationService (process)
              └──► StringRedisTemplate (DLQ XADD)

InstagramOAuthController
    └──► InstagramOAuthService
              ├──► MetaGraphApiClient
              ├──► TokenEncryptionService
              └──► InstagramOAuthTokenRepository
```

---

## Pipeline Stages (Overview)

| Stage | Name | Trigger | Class |
|---|---|---|---|
| 1 | INGEST | HTTP POST from Meta | `EventPipelineIntegrationService.ingest()` |
| 2 | QUEUE | After idempotency pass | `WebhookEventProducer.pushToStream()` |
| 3 | CONSUME + NORMALIZE | Redis Stream poll | `WebhookStreamConsumer.onMessage()` |
| 4 | ORCHESTRATE + DECIDE | Per normalized message | `WebhookEventOrchestrator.process()` → `AutomationEngine.evaluate()` |
| 5 | SEND | Per fired rule | `RuleReplyDispatcher.dispatch()` → `InstagramMessageSenderService` |

---

## Data Flow — Synchronous vs. Asynchronous

```
HTTP Thread (synchronous, < 50ms)
────────────────────────────────────────────────────────
  Meta POST → validate sig → idempotency check → XADD → HTTP 200

                    ┌── async boundary (Redis Stream) ──┐

Background Thread (asynchronous, pooled)
────────────────────────────────────────────────────────
  XREADGROUP → normalize → resolve tenant → orchestrate → evaluate rules → send reply
```

The HTTP thread is released before any normalization, rule evaluation, or Meta API call occurs. This ensures Meta always receives a `200 OK` within the 20-second timeout window.

---

## Multi-Tenancy Architecture

Every entity and query is scoped by `tenant_id`:

- **Application layer**: `tenant_id` is extracted from the JWT/session and stored in a thread-local `TenantContext`. All repository queries include it explicitly.
- **Database layer**: PostgreSQL Row-Level Security (RLS) policies use `current_setting('app.current_tenant')` to filter every row automatically, even if application code forgets to filter.
- **Cache layer**: `RuleCache` keys are `tenantId:igAccountId`, providing perfect tenant isolation with no cross-contamination.
- **Redis layer**: All keys are prefixed with logical tenant or account scopes (e.g., `rate_limit:ig_account:{id}`).

---

## Spring Boot Configuration Summary

| Property | Default | Description |
|---|---|---|
| `spring.threads.virtual.enabled` | `true` | Java 21 virtual threads for all request handling |
| `spring.datasource.hikari.maximum-pool-size` | `20` | Max DB connection pool size |
| `spring.data.redis.lettuce.pool.max-active` | `16` | Max Redis connections |
| `axion.meta.graph-api-version` | `v20.0` | Meta Graph API version |
| `axion.automation.default-cooldown-seconds` | `3600` | Per-rule cooldown override |
| `axion.automation.max-rules-per-message` | `10` | Safety cap for RUN_ALL mode |
| `axion.automation.eager-cache-warmup` | `false` | Set `true` in production for predictable p99 |
