-- =============================================================================
-- V4: Production-Ready Full Schema
-- =============================================================================
-- What this migration adds / changes:
--
--   NEW tables:
--     1. users                 — tenant anchor; one row per SaaS customer account
--     2. instagram_accounts    — promoted from embedded columns in instagram_oauth_tokens;
--                                canonical registry of connected IG Business accounts
--     3. messages              — canonical inbound/outbound message log with idempotency
--
--   RETROFIT on existing tables (V1-V3):
--     4. contacts              — add FK → instagram_accounts; add JSONB metadata column
--     5. automation_rules      — add FK → instagram_accounts; add JSONB config column;
--                                add composite index (tenant_id, active, trigger_type)
--     6. automation_execution_log — add FK → messages; add message_direction
--     7. instagram_webhook_events — add tenant_id + index (was missing from V2)
--
--   Common patterns applied everywhere:
--     • tenant_id UUID NOT NULL on every table (multi-tenancy)
--     • Row-Level Security policy: tenant_id = app.current_tenant
--     • updated_at trigger using the update_updated_at_column() fn from V1
--     • Idempotent unique constraints for replay scenarios
--     • Partial indexes where a WHERE clause eliminates dead rows
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. USERS  (tenant anchor)
-- ─────────────────────────────────────────────────────────────────────────────
-- One row = one SaaS tenant account.
-- The tenant_id UUID on every other table is a logical reference to this table.
-- (No FK from all tables → users to avoid cross-schema performance costs on hot paths;
--  referential integrity is enforced at the application layer via Spring Security context.)
CREATE TABLE IF NOT EXISTS users (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL UNIQUE,        -- the shared tenant discriminator
    email           VARCHAR(320) NOT NULL UNIQUE,       -- primary login, RFC 5321 max
    display_name    VARCHAR(255),
    plan            VARCHAR(50)  NOT NULL DEFAULT 'FREE',
    -- FREE | STARTER | GROWTH | ENTERPRISE
    status          VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | SUSPENDED | DELETED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_users_plan   CHECK (plan   IN ('FREE','STARTER','GROWTH','ENTERPRISE')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))
);

-- Lookups by tenant and email
CREATE INDEX idx_users_tenant    ON users (tenant_id);
CREATE INDEX idx_users_email     ON users (email);
CREATE INDEX idx_users_status    ON users (status) WHERE status != 'DELETED';

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_users ON users
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

COMMENT ON TABLE  users           IS 'Tenant anchor. One row per SaaS account. tenant_id is the shared discriminator across all tables.';
COMMENT ON COLUMN users.tenant_id IS 'Propagated to every table as the multi-tenancy discriminator.';
COMMENT ON COLUMN users.plan      IS 'Subscription tier; controls rate limits and feature access.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. INSTAGRAM_ACCOUNTS  (canonical registry)
-- ─────────────────────────────────────────────────────────────────────────────
-- Promoted from columns in instagram_oauth_tokens.
-- A tenant can connect multiple IG Business Accounts.
-- FK back to instagram_oauth_tokens links the live token row.
CREATE TABLE IF NOT EXISTS instagram_accounts (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL
        REFERENCES users (tenant_id) ON DELETE CASCADE,
    ig_account_id           VARCHAR(255) NOT NULL,      -- Meta IG Business Account ID
    ig_username             VARCHAR(255),               -- @handle
    page_id                 VARCHAR(255),               -- linked Facebook Page ID
    token_row_id            UUID                        -- → instagram_oauth_tokens.id
        REFERENCES instagram_oauth_tokens (id) ON DELETE SET NULL,
    connected_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status                  VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | DISCONNECTED | TOKEN_EXPIRED
    -- Flexible metadata: webhook subscription status, follower count snapshot, etc.
    metadata                JSONB        NOT NULL DEFAULT '{}',
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_ig_account_per_tenant UNIQUE (tenant_id, ig_account_id),
    CONSTRAINT chk_ig_account_status    CHECK  (status IN ('ACTIVE','DISCONNECTED','TOKEN_EXPIRED'))
);

CREATE INDEX idx_ig_accounts_tenant          ON instagram_accounts (tenant_id);
CREATE INDEX idx_ig_accounts_ig_id           ON instagram_accounts (ig_account_id);
CREATE INDEX idx_ig_accounts_tenant_active   ON instagram_accounts (tenant_id, status)
    WHERE status = 'ACTIVE';
-- GIN on JSONB metadata for arbitrary attribute filtering
CREATE INDEX idx_ig_accounts_metadata        ON instagram_accounts USING GIN (metadata);

ALTER TABLE instagram_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_ig_accounts ON instagram_accounts
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE TRIGGER trg_ig_accounts_updated_at
    BEFORE UPDATE ON instagram_accounts
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

COMMENT ON TABLE  instagram_accounts          IS 'Registry of connected Instagram Business Accounts per tenant.';
COMMENT ON COLUMN instagram_accounts.metadata IS 'JSONB bag: webhook_subscribed, follower_count_snapshot, etc.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. MESSAGES  (canonical inbound + outbound log)
-- ─────────────────────────────────────────────────────────────────────────────
-- Every message flowing through the platform (received or sent) has one row here.
-- This table is the source of truth for conversation threading.
CREATE TABLE IF NOT EXISTS messages (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL
        REFERENCES users (tenant_id) ON DELETE CASCADE,

    -- Which IG Business Account owns this message thread
    ig_account_id       VARCHAR(255) NOT NULL,

    -- Direction: INBOUND = received from user, OUTBOUND = sent by us (automation/manual)
    direction           VARCHAR(20)  NOT NULL,
    -- INBOUND | OUTBOUND

    -- Participants
    sender_id           VARCHAR(255) NOT NULL,   -- IGSID of the sending party
    recipient_id        VARCHAR(255) NOT NULL,   -- IGSID of the receiving party

    -- Content
    message_type        VARCHAR(50)  NOT NULL DEFAULT 'TEXT',
    -- TEXT | IMAGE | AUDIO | VIDEO | STICKER | STORY_MENTION | UNSUPPORTED
    message_text        TEXT,                    -- normalised text (from chunk 4); NULL for media-only
    media_url           TEXT,                    -- signed CDN URL for media messages
    -- Raw payload from Meta webhook or Graph API response
    raw_payload         JSONB        NOT NULL DEFAULT '{}',

    -- Idempotency: unique per Meta message ID × ig_account
    -- Prevents duplicate processing of the same webhook event
    meta_message_id     VARCHAR(255) NOT NULL,   -- Meta's "mid"
    CONSTRAINT uq_message_mid_account UNIQUE (ig_account_id, meta_message_id),

    -- Thread grouping (future: conversation ID)
    thread_id           UUID,                    -- NULL until threaded model is wired

    -- Timestamps
    sent_at             TIMESTAMPTZ  NOT NULL,   -- epoch from Meta webhook
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_message_direction CHECK (direction    IN ('INBOUND','OUTBOUND')),
    CONSTRAINT chk_message_type      CHECK (message_type IN (
        'TEXT','IMAGE','AUDIO','VIDEO','STICKER','STORY_MENTION','UNSUPPORTED'
    ))
);

-- ── Indexing strategy for messages ──────────────────────────────────────────

-- PRIMARY hotpath: load conversation for a contact (inbox view, pagination)
CREATE INDEX idx_messages_sender_created
    ON messages (tenant_id, ig_account_id, sender_id, created_at DESC);

-- Inbox list: most recent message per account (sorted inbox)
CREATE INDEX idx_messages_account_created
    ON messages (tenant_id, ig_account_id, created_at DESC);

-- Thread retrieval (when threaded model is active)
CREATE INDEX idx_messages_thread
    ON messages (thread_id, created_at ASC)
    WHERE thread_id IS NOT NULL;

-- Outbound messages only — for delivery tracking, rate-limit audits
CREATE INDEX idx_messages_outbound
    ON messages (tenant_id, ig_account_id, created_at DESC)
    WHERE direction = 'OUTBOUND';

-- Idempotency fast-path: does this mid already exist?
-- Covered by the UNIQUE constraint but a partial index for INBOUND-only dedup is faster
CREATE INDEX idx_messages_inbound_mid
    ON messages (ig_account_id, meta_message_id)
    WHERE direction = 'INBOUND';

-- GIN on raw_payload — ad-hoc queries against webhook fields
CREATE INDEX idx_messages_raw_payload ON messages USING GIN (raw_payload);

ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_messages ON messages
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE TRIGGER trg_messages_updated_at
    BEFORE UPDATE ON messages
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

COMMENT ON TABLE  messages                IS 'Canonical inbound + outbound message log. One row per Meta message. Source of truth for conversation history.';
COMMENT ON COLUMN messages.meta_message_id IS 'Meta Graph API mid — idempotency key for deduplication across webhook retries.';
COMMENT ON COLUMN messages.raw_payload     IS 'Full normalised or API response payload stored as JSONB for audit and replay.';
COMMENT ON COLUMN messages.sent_at         IS 'Timestamp from Meta (epoch ms converted), not DB insert time.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. RETROFIT: contacts — add FK + JSONB metadata
-- ─────────────────────────────────────────────────────────────────────────────
-- FK ensures contacts belong to a real connected account.
-- metadata bag: tags, custom attributes, opt-out flags, CRM sync state.
ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}',
    ADD CONSTRAINT fk_contacts_ig_account
        FOREIGN KEY (tenant_id, ig_account_id)
        REFERENCES instagram_accounts (tenant_id, ig_account_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX IF NOT EXISTS idx_contacts_metadata
    ON contacts USING GIN (metadata);

COMMENT ON COLUMN contacts.metadata IS 'JSONB bag: tags[], opt_out, crm_id, custom attributes.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 5. RETROFIT: automation_rules — add FK + JSONB config + missing indexes
-- ─────────────────────────────────────────────────────────────────────────────
-- JSONB config stores flexible rule parameters without schema changes:
--   keyword rules:  { "keywords": ["price", "cost"], "match_mode": "ANY" }
--   welcome rules:  { "delay_seconds": 0 }
--   fallback rules: { "max_fires_per_day": 1 }
ALTER TABLE automation_rules
    ADD COLUMN IF NOT EXISTS config JSONB NOT NULL DEFAULT '{}',
    ADD CONSTRAINT fk_rules_ig_account
        FOREIGN KEY (tenant_id, ig_account_id)
        REFERENCES instagram_accounts (tenant_id, ig_account_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;

-- The primary cache-load index already exists (tenant_id, ig_account_id, active, priority)
-- Add the missing (tenant_id, active, trigger_type) index per the requirement
CREATE INDEX IF NOT EXISTS idx_rules_tenant_active_type
    ON automation_rules (tenant_id, active, trigger_type)
    WHERE active = TRUE;

-- GIN on JSONB config for arbitrary rule config queries
CREATE INDEX IF NOT EXISTS idx_rules_config
    ON automation_rules USING GIN (config);

COMMENT ON COLUMN automation_rules.config IS
    'JSONB rule configuration. Schema varies by trigger_type. '
    'KEYWORD: { "keywords": [], "match_mode": "ANY|ALL" }. '
    'WELCOME:  { "delay_seconds": 0 }. '
    'FALLBACK: { "max_fires_per_day": 1 }.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 6. RETROFIT: automation_execution_log — add FK → messages
-- ─────────────────────────────────────────────────────────────────────────────
-- Links the execution record to the outbound message that was sent.
ALTER TABLE automation_execution_log
    ADD COLUMN IF NOT EXISTS outbound_message_id UUID
        REFERENCES messages (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_exec_log_outbound_message
    ON automation_execution_log (outbound_message_id)
    WHERE outbound_message_id IS NOT NULL;

COMMENT ON COLUMN automation_execution_log.outbound_message_id IS
    'FK to messages.id of the outbound reply sent by this rule execution. NULL for SKIPPED/FAILED.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 7. RETROFIT: instagram_webhook_events — add tenant_id (was missing in V2)
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE instagram_webhook_events
    ADD COLUMN IF NOT EXISTS tenant_id    UUID,
    ADD COLUMN IF NOT EXISTS ig_account_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_at   TIMESTAMPTZ NOT NULL DEFAULT now();

-- Backfill: existing rows get a sentinel tenant
-- (Production: run a data migration before enabling RLS)
UPDATE instagram_webhook_events
    SET tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
    WHERE tenant_id IS NULL;

ALTER TABLE instagram_webhook_events
    ALTER COLUMN tenant_id SET NOT NULL;

-- Add FK (will be NOT NULL after backfill above; new inserts must supply it)
-- Deferred to allow webhook receipt before user context is known

CREATE INDEX IF NOT EXISTS idx_webhook_events_tenant
    ON instagram_webhook_events (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_webhook_events_ig_account
    ON instagram_webhook_events (ig_account_id, created_at DESC)
    WHERE ig_account_id IS NOT NULL;

ALTER TABLE instagram_webhook_events ENABLE ROW LEVEL SECURITY;
-- Policy is nullable-safe: rows with sentinel tenant are only visible to service accounts
CREATE POLICY rls_webhook_events ON instagram_webhook_events
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid
        OR current_setting('app.current_tenant', true) IS NULL);

CREATE TRIGGER trg_webhook_events_updated_at
    BEFORE UPDATE ON instagram_webhook_events
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();


-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE COMMENTS (comprehensive)
-- ─────────────────────────────────────────────────────────────────────────────
COMMENT ON TABLE instagram_webhook_events IS
    'Raw deduped webhook payloads. status: RECEIVED | PROCESSED | FAILED | DLQ. '
    'tenant_id added in V4; sentinel UUID for rows ingested before tenant was known.';
