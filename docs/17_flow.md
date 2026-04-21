# Event Flow — End-to-End Lifecycle

## Complete Flow Diagram

```
  ╔══════════════════════════════════════════════════════════════════════════════╗
  ║                         INSTAGRAM USER                                       ║
  ║               Sends a DM to a connected Business Account                     ║
  ╚══════════════════════════════════╦═══════════════════════════════════════════╝
                                     ║
                                     ║  Meta Webhook POST
                                     ║  X-Hub-Signature-256: sha256={hmac}
                                     ▼
  ╔══════════════════════════════════════════════════════════════════════════════╗
  ║  STAGE 1: INGEST (Synchronous HTTP — < 50ms)                                ║
  ║                                                                              ║
  ║  WebhookController.receiveEvents()                                           ║
  ║      │                                                                       ║
  ║      ├─ Extract eventId from payload (mid → timestamp → hash fallback)      ║
  ║      └─ EventPipelineIntegrationService.ingest(payload, sigHeader, eventId) ║
  ║              │                                                               ║
  ║              ├─[1a] WebhookSignatureValidator.isValidSignature()             ║
  ║              │       FAIL → IngestResult.REJECTED → HTTP 403                ║
  ║              │       PASS ↓                                                  ║
  ║              ├─[1b] WebhookIdempotencyService.processAndCheckIdempotency()  ║
  ║              │       Layer 1: Redis SETNX idempotency:webhook:{eventId}     ║
  ║              │       Layer 2: DB INSERT (UNIQUE constraint on event_id)     ║
  ║              │       DUPLICATE → IngestResult.DUPLICATE → HTTP 200          ║
  ║              │       NEW ↓                                                   ║
  ║              └─[2]  WebhookEventProducer.pushToStream()                     ║
  ║                      XADD instagram-webhooks * event_id {id} payload {json} ║
  ║                      FAIL (Redis down) → rethrow → HTTP 500 → Meta retries ║
  ║                      OK → IngestResult.ACCEPTED → HTTP 200 "EVENT_RECEIVED" ║
  ║                                                                              ║
  ║  ← Meta receives HTTP 200 immediately. Flow continues asynchronously →      ║
  ╚══════════════════════════════════════════════════════════════════════════════╝
                                     ║
                                     ║  Redis Stream: instagram-webhooks
                                     ║  Consumer Group: axion-processors
                                     ▼
  ╔══════════════════════════════════════════════════════════════════════════════╗
  ║  STAGE 3: CONSUME + NORMALIZE (Async — background virtual thread)           ║
  ║                                                                              ║
  ║  WebhookStreamConsumer.onMessage(MapRecord)                                 ║
  ║      │                                                                       ║
  ║      ├─ Extract event_id, payload from stream record                        ║
  ║      ├─ Extract igAccountId from payload JSON                               ║
  ║      ├─ resolveTenant(igAccountId)                                          ║
  ║      │     → tokenRepository.findFirstByInstagramAccountIdAndStatus(ACTIVE) ║
  ║      │     FAIL (no tenant) → parkToDlq(NO_TENANT_TOKEN)                   ║
  ║      │     OK: tenantId resolved ↓                                          ║
  ║      ├─ WebhookNormalizationService.normalize(payload, tenantId, igAcctId) ║
  ║      │     → WebhookPayloadParser.parse() → List<MessageDTO>               ║
  ║      │     → For each DTO: MessageDTOValidator.validate()                  ║
  ║      │          INVALID → parkToDlq(validation error code)                 ║
  ║      │          VALID ↓                                                     ║
  ║      └─ For each valid MessageDTO: orchestrator.process(...)               ║
  ║                FAIL (any exception) → parkToDlq(CONSUMER_ERROR)            ║
  ║                OK → XACK (removes from PEL)                                ║
  ╚══════════════════════════════════════════════════════════════════════════════╝
                                     ║
                                     ▼
  ╔══════════════════════════════════════════════════════════════════════════════╗
  ║  STAGE 4: ORCHESTRATE + DECIDE (per MessageDTO)                             ║
  ║                                                                              ║
  ║  WebhookEventOrchestrator.process(tenantId, igAccountId, message, eventId) ║
  ║      │                                                                       ║
  ║      ├─ statusUpdater.markProcessing(eventId)   [REQUIRES_NEW transaction] ║
  ║      ├─ tokenRepository.findByTenantIdAndInstagramAccountId(...)           ║
  ║      ├─ Validate token: status == ACTIVE && !isExpired()                    ║
  ║      ├─ encryptionService.decrypt(ciphertext, iv, tag) → plainTextToken    ║
  ║      └─ automationEngine.evaluate(tenantId, message)                       ║
  ║              │                                                               ║
  ║      ╔───────────────────────────────────────────────────────────╗          ║
  ║      ║  AUTOMATION ENGINE                                         ║          ║
  ║      ║  1. contactRepository.upsertContact() → isFirstInteraction║          ║
  ║      ║  2. ruleCache.getOrLoad(tenantId, igAccountId)            ║          ║
  ║      ║        cache hit  → RuleSet (sub-millisecond)             ║          ║
  ║      ║        cache miss → DB load → build inverted keyword index ║          ║
  ║      ║  3. Candidate selection:                                   ║          ║
  ║      ║        a. isFirstInteraction → WELCOME rules              ║          ║
  ║      ║        b. tokenize text → probe keywordIndex → KEYWORD     ║          ║
  ║      ║        c. still empty → FALLBACK rules                    ║          ║
  ║      ║  4. Sort by priority ASC, deduplicate by ruleId           ║          ║
  ║      ║  5. For each candidate:                                    ║          ║
  ║      ║        ├─ cooldown check (DB: existsWithinCooldown)       ║          ║
  ║      ║        │   HIT → log SKIPPED, continue                    ║          ║
  ║      ║        ├─ resolveTemplate(replyText, message)             ║          ║
  ║      ║        ├─ replyDispatcher.dispatch(...)                   ║          ║
  ║      ║        └─ log SENT/FAILED → AutomationExecutionLog        ║          ║
  ║      ║  FIRST_MATCH: break after first SENT                      ║          ║
  ║      ╚───────────────────────────────────────────────────────────╝          ║
  ║      │                                                                       ║
  ║      └─ statusUpdater.markProcessed/Failed(eventId)  [REQUIRES_NEW]        ║
  ╚══════════════════════════════════════════════════════════════════════════════╝
                                     ║
                                     ▼
  ╔══════════════════════════════════════════════════════════════════════════════╗
  ║  STAGE 5: SEND (per fired rule)                                             ║
  ║                                                                              ║
  ║  RuleReplyDispatcher.dispatch(tenantId, message, rule, resolvedReply)       ║
  ║      └─ InstagramMessageSenderService.sendMessage(request, token)           ║
  ║              │                                                               ║
  ║              ├─ GraphApiRateLimiter.acquireOrThrow(igAccountId)             ║
  ║              │     ZADD rate_limit:ig_account:{id} → ZCARD                 ║
  ║              │     OVER LIMIT → throw RateLimitExceededException            ║
  ║              │                                                               ║
  ║              └─ @Retry + @CircuitBreaker(meta-graph-api)                    ║
  ║                    MetaGraphApiClient.sendMessage()                          ║
  ║                    POST /{igAccountId}/messages                              ║
  ║                      Body: { recipient: {id}, message: {text} }            ║
  ║                      Auth: Bearer {decrypted access token}                  ║
  ║                                                                              ║
  ║                    SUCCESS → { recipient_id, message_id }                   ║
  ║                    TRANSIENT (5xx, timeout) → retry ×4 (1s→2s→4s)         ║
  ║                    PERMANENT (4xx) → PermanentApiException (not retried)    ║
  ║                    CB OPEN → fallbackMethod() → PermanentApiException       ║
  ╚══════════════════════════════════════════════════════════════════════════════╝
```

---

## Example Lifecycle — Keyword-Triggered Reply

**Scenario:** A user sends `"What are your prices?"` to a business that has a KEYWORD rule matching `"price"` or `"pricing"`.

### Chronological Timeline

```
T+0ms   Meta sends POST /api/v1/webhooks/instagram
        X-Hub-Signature-256: sha256=a1b2c3...

T+2ms   HMAC-SHA256 validation passes

T+3ms   Redis SETNX idempotency:webhook:mid.001 → 1 (new)
        DB INSERT instagram_webhook_events (status=RECEIVED)

T+5ms   XADD instagram-webhooks * event_id mid.001 payload {...}
        IngestResult.ACCEPTED → HTTP 200 returned to Meta

        [async consumer picks up the stream record]

T+15ms  WebhookStreamConsumer reads from axion-processors consumer group
        igAccountId = "17841400001" extracted from payload

T+16ms  tokenRepository.findFirstByInstagramAccountIdAndStatus(17841400001, ACTIVE)
        → tenantId = uuid-of-tenant-1

T+18ms  WebhookPayloadParser.parse() → MessageDTO {
            messageId: "mid.001",
            senderId:  "10158001",
            messageType: TEXT,
            messageText: "what are your prices?" (lowercased by normalizer)
        }

T+19ms  MessageDTOValidator.validate():
        Rule 1: senderId "10158001" ✓ (numeric, ≥6 digits)
        Rule 2: timestamp not in future, not > 24h old ✓
        Rule 3: messageType TEXT != UNKNOWN ✓
        Rule 4: text length 21 ≤ 2048 ✓
        → ValidationResult.valid()

T+20ms  WebhookEventOrchestrator.process() starts
        statusUpdater.markProcessing("mid.001") → DB: status=PROCESSING

T+21ms  Token lookup + AES-256-GCM decryption → plainTextToken

T+22ms  AutomationEngine.evaluate():
        contactRepository.upsertContact() → message_count=3 → NOT first interaction

T+23ms  ruleCache.getOrLoad(uuid-of-tenant-1, 17841400001)
        → Cache HIT (already loaded)
        → RuleSet { welcomeRules: [], fallbackRules: [rule-2], keywordIndex: {"price": [rule-1], ...} }

T+24ms  Candidate selection:
        a. NOT first interaction → no WELCOME rules
        b. tokens = {"what", "are", "your", "prices?"}
           keywordIndex.get("price") → [rule-1]
           (Note: "prices?" won't match "price" exactly — full token equality)
           → Try alternative: "pricing" in keywordIndex → [rule-1] (if keyword stored)
        c. candidates = [rule-1] (KEYWORD, priority=10)

T+25ms  cooldown check: rule-1 for sender 10158001
        executionLogRepository.existsWithinCooldown(... rule-1, since=T-1h)
        → false (first message in last hour)

T+26ms  resolveTemplate("Hi {sender_id}! Our pricing starts at $49/mo.")
        → "Hi 10158001! Our pricing starts at $49/mo."

T+27ms  GraphApiRateLimiter.acquireOrThrow(17841400001)
        ZADD rate_limit:ig_account:17841400001 {nowMs} {nowMs}
        ZCARD → 1 (well under 200/hour limit)

T+28ms  MetaGraphApiClient.sendMessage()
        POST https://graph.facebook.com/v20.0/17841400001/messages
        Body: { "recipient": {"id": "10158001"}, "message": {"text": "Hi 10158001! Our pricing starts at $49/mo."} }
        → HTTP 200 { "recipient_id": "10158001", "message_id": "mid.reply.001" }

T+30ms  AutomationExecutionLog saved: status=SENT, ruleId=rule-1
        FIRST_MATCH: break (no more candidates to evaluate)

T+31ms  statusUpdater.markProcessed("mid.001") → DB: status=PROCESSED

T+31ms  XACK instagram-webhooks axion-processors {recordId}

Total pipeline latency: ~31ms (< 100ms P99 target)
```

---

## DLQ Lifecycle — Recovery Flow

```
Event mid.002 arrives with igAccountId "17841499999"
  │
  ├─ HMAC validates OK
  ├─ Idempotency passes (new event_id)
  ├─ Queued to instagram-webhooks
  │
  ├─ Consumer picks up:
  │     resolveTenant("17841499999") → null (no ACTIVE token)
  │     parkToDlq(mid.002, NO_TENANT_TOKEN, "No active token for igAccountId=17841499999")
  │     statusUpdater.markDlq(mid.002, ...)
  │
  │   [instagram_webhook_events: status=DLQ]
  │   [instagram-webhooks-dlq: entry {event_id=mid.002, error_code=NO_TENANT_TOKEN}]
  │
  │   ... Operator connects the Instagram account via OAuth ...
  │   Token ACTIVE in DB for tenant uuid-of-tenant-99
  │
  ├─ POST /api/v1/webhooks/instagram/dlq/replay?limit=5
  │     DlqReplayService.replayOne(dlqRecord, "mid.002")
  │     → eventRepository.findByEventId("mid.002").getPayload() → original JSON
  │     → XADD instagram-webhooks * event_id mid.002 payload {original} replay true
  │     → XDEL instagram-webhooks-dlq {recordId}
  │
  └─ Consumer re-processes:
        resolveTenant("17841499999") → now returns uuid-of-tenant-99 ✓
        ... continues to normalization → engine → send ...
        XACK
```

---

## OAuth Connection Lifecycle

```
Tenant admin navigates to /connect page in frontend
    │
    ├─ GET /api/v1/oauth/instagram/authorize?tenantId=...&userId=...
    │     stateToken = UUID.randomUUID()
    │     SET oauth:state:{stateToken} {json} EX 600
    │     Returns authorizationUrl = https://www.facebook.com/dialog/oauth?...&state={stateToken}
    │
    ├─ Frontend redirects user to authorizationUrl
    │
    ├─ User approves permissions on Meta
    │
    └─ Meta redirects to GET /api/v1/oauth/instagram/callback?code={code}&state={stateToken}
            GETDEL oauth:state:{stateToken} → verify CSRF state
            graphApiClient.exchangeCodeForToken(code) → shortLivedToken
            graphApiClient.extendToken(shortLivedToken) → longLivedToken (60 days)
            graphApiClient.fetchInstagramBusinessAccount(token) → igAccountId, username
            encryptionService.encrypt(longLivedToken) → {ciphertext, iv, tag}
            tokenRepository.save(InstagramOAuthToken{...status=ACTIVE})
            Returns OAuthConnectionResult{instagramAccountId, tokenExpiry, status=ACTIVE, isNew=true}
```
