-- =============================================================================
-- AxionAuto — Sample Production Queries
-- =============================================================================
-- All queries assume:
--   SET LOCAL app.current_tenant = '<tenant-uuid>';
-- before execution so RLS policies activate.
-- =============================================================================


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 1: Inbox view — last message per contact for a business account
-- ─────────────────────────────────────────────────────────────────────────────
-- Use-case: render the CRM inbox list sorted by latest activity.
-- Index hit: idx_messages_sender_created (tenant_id, ig_account_id, sender_id, created_at DESC)

SELECT DISTINCT ON (m.sender_id)
    m.sender_id,
    m.message_text,
    m.direction,
    m.sent_at,
    c.metadata ->> 'display_name'   AS contact_name,
    c.interaction_count
FROM   messages  m
JOIN   contacts  c
       ON  c.tenant_id    = m.tenant_id
       AND c.ig_account_id = m.ig_account_id
       AND c.sender_id     = m.sender_id
WHERE  m.tenant_id    = current_setting('app.current_tenant')::uuid
  AND  m.ig_account_id = :igAccountId        -- bind param
ORDER BY m.sender_id, m.sent_at DESC;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 2: Conversation thread for a specific contact (paginated)
-- ─────────────────────────────────────────────────────────────────────────────
-- Use-case: open a DM conversation thread, newest-first, cursor-based pagination.
-- Index hit: idx_messages_sender_created

SELECT
    m.id,
    m.direction,
    m.message_type,
    m.message_text,
    m.media_url,
    m.sent_at,
    m.meta_message_id
FROM   messages m
WHERE  m.tenant_id     = current_setting('app.current_tenant')::uuid
  AND  m.ig_account_id = :igAccountId
  AND  m.sender_id     = :senderId
  AND  m.sent_at       < :cursorSentAt        -- cursor pagination
ORDER BY m.sent_at DESC
LIMIT  :pageSize;                             -- e.g. 30


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 3: Idempotent message insert (dedup on meta_message_id)
-- ─────────────────────────────────────────────────────────────────────────────
-- Use-case: ingest from webhook. Ignore if already seen (UNIQUE on ig_account_id + mid).

INSERT INTO messages (
    tenant_id, ig_account_id, direction,
    sender_id, recipient_id,
    message_type, message_text,
    raw_payload, meta_message_id, sent_at
)
VALUES (
    :tenantId, :igAccountId, 'INBOUND',
    :senderId, :recipientId,
    'TEXT', :normalizedText,
    :rawPayloadJsonb, :metaMessageId, :sentAt
)
ON CONFLICT ON CONSTRAINT uq_message_mid_account DO NOTHING
RETURNING id;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 4: Load active automation rules for the engine cache
-- ─────────────────────────────────────────────────────────────────────────────
-- Use-case: RuleCache.load() — one query, keywords joined in.
-- Index hit: idx_rules_tenant_account_active

SELECT
    r.id,
    r.name,
    r.trigger_type,
    r.execution_mode,
    r.reply_text,
    r.config,
    r.priority,
    r.cooldown_seconds,
    array_agg(k.keyword) FILTER (WHERE k.keyword IS NOT NULL) AS keywords
FROM   automation_rules r
LEFT   JOIN rule_keywords k ON k.rule_id = r.id
WHERE  r.tenant_id    = current_setting('app.current_tenant')::uuid
  AND  r.ig_account_id = :igAccountId
  AND  r.active        = TRUE
GROUP  BY r.id
ORDER  BY r.priority ASC;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 5: Cooldown check — did this rule fire for this sender recently?
-- ─────────────────────────────────────────────────────────────────────────────
-- Use-case: AutomationEngine pre-execution guard.
-- Index hit: idx_exec_log_cooldown (tenant_id, ig_account_id, sender_id, rule_id, executed_at DESC)

SELECT EXISTS (
    SELECT 1
    FROM   automation_execution_log
    WHERE  tenant_id    = current_setting('app.current_tenant')::uuid
      AND  ig_account_id = :igAccountId
      AND  sender_id     = :senderId
      AND  rule_id       = :ruleId
      AND  status        = 'SENT'
      AND  executed_at  >= now() - (:cooldownSeconds || ' seconds')::interval
) AS within_cooldown;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 6: Contact upsert — first-interaction detection (atomic)
-- ─────────────────────────────────────────────────────────────────────────────
-- Use-case: every inbound message upserts the contact row.
-- interaction_count = 1 after this call → first interaction.

INSERT INTO contacts (tenant_id, ig_account_id, sender_id, first_seen_at, last_seen_at, interaction_count)
VALUES (:tenantId, :igAccountId, :senderId, now(), now(), 1)
ON CONFLICT ON CONSTRAINT uq_contact
DO UPDATE SET
    last_seen_at      = EXCLUDED.last_seen_at,
    interaction_count = contacts.interaction_count + 1
RETURNING interaction_count;   -- 1 = first interaction, >1 = repeat


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 7: Rules matching a specific trigger type (for admin UI)
-- ─────────────────────────────────────────────────────────────────────────────
-- Index hit: idx_rules_tenant_active_type

SELECT
    r.id,
    r.name,
    r.trigger_type,
    r.priority,
    r.config,
    r.cooldown_seconds,
    r.created_at
FROM   automation_rules r
WHERE  r.tenant_id    = current_setting('app.current_tenant')::uuid
  AND  r.active        = TRUE
  AND  r.trigger_type  = :triggerType       -- 'KEYWORD' | 'WELCOME' | 'FALLBACK'
ORDER  BY r.priority ASC;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 8: JSONB config query — find all rules with a specific keyword
-- ─────────────────────────────────────────────────────────────────────────────
-- Works because rule keywords are stored in BOTH rule_keywords table AND config JSONB.
-- Index hit: idx_rules_config (GIN)

SELECT r.id, r.name, r.config
FROM   automation_rules r
WHERE  r.tenant_id = current_setting('app.current_tenant')::uuid
  AND  r.active    = TRUE
  AND  r.config @> '{"keywords": ["price"]}'::jsonb;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 9: Automation performance report — rules fired per day
-- ─────────────────────────────────────────────────────────────────────────────
-- Use-case: analytics dashboard tile.

SELECT
    date_trunc('day', executed_at) AS day,
    r.name                          AS rule_name,
    r.trigger_type,
    COUNT(*)  FILTER (WHERE l.status = 'SENT')    AS sent_count,
    COUNT(*)  FILTER (WHERE l.status = 'SKIPPED') AS skipped_count,
    COUNT(*)  FILTER (WHERE l.status = 'FAILED')  AS failed_count
FROM   automation_execution_log l
JOIN   automation_rules         r ON r.id = l.rule_id
WHERE  l.tenant_id    = current_setting('app.current_tenant')::uuid
  AND  l.ig_account_id = :igAccountId
  AND  l.executed_at  >= now() - interval '30 days'
GROUP  BY day, r.name, r.trigger_type
ORDER  BY day DESC, sent_count DESC;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 10: Connected accounts for a tenant (account switcher UI)
-- ─────────────────────────────────────────────────────────────────────────────

SELECT
    ia.id,
    ia.ig_account_id,
    ia.ig_username,
    ia.status,
    ia.connected_at,
    ia.metadata ->> 'webhook_subscribed' AS webhook_subscribed,
    t.token_expiry,
    t.status                              AS token_status
FROM   instagram_accounts  ia
LEFT   JOIN instagram_oauth_tokens t ON t.id = ia.token_row_id
WHERE  ia.tenant_id = current_setting('app.current_tenant')::uuid
ORDER  BY ia.connected_at DESC;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 11: Unprocessed webhook events (worker queue poll)
-- ─────────────────────────────────────────────────────────────────────────────
-- Uses SKIP LOCKED so multiple workers can process concurrently without conflicts.

SELECT id, event_id, payload, created_at
FROM   instagram_webhook_events
WHERE  status = 'RECEIVED'
ORDER  BY created_at ASC
LIMIT  50
FOR UPDATE SKIP LOCKED;


-- ─────────────────────────────────────────────────────────────────────────────
-- SCENARIO 12: Find contacts tagged with a custom label (JSONB array query)
-- ─────────────────────────────────────────────────────────────────────────────
-- Index hit: idx_contacts_metadata (GIN)

SELECT
    c.sender_id,
    c.last_seen_at,
    c.interaction_count,
    c.metadata -> 'tags' AS tags
FROM   contacts c
WHERE  c.tenant_id     = current_setting('app.current_tenant')::uuid
  AND  c.ig_account_id  = :igAccountId
  AND  c.metadata       @> '{"tags": ["vip"]}'::jsonb
ORDER  BY c.last_seen_at DESC;
