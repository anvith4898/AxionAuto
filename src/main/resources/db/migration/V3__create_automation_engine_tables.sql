-- V3: Automation Engine tables
-- contacts, automation_rules, rule_keywords, automation_execution_log
-- Multi-tenant, Row-Level-Security mirroring V1 pattern.

-- ─── Contacts ──────────────────────────────────────────────────────────────────
-- One row per unique (tenant, ig_account, sender) pair.
-- "first_seen_at" drives the Welcome-message trigger.
CREATE TABLE contacts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    ig_account_id       VARCHAR(255) NOT NULL,   -- recipient business account
    sender_id           VARCHAR(255) NOT NULL,   -- IGSID of the external user
    first_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    interaction_count   BIGINT       NOT NULL DEFAULT 1,
    CONSTRAINT uq_contact UNIQUE (tenant_id, ig_account_id, sender_id)
);

CREATE INDEX idx_contacts_tenant_account ON contacts (tenant_id, ig_account_id);
CREATE INDEX idx_contacts_sender         ON contacts (tenant_id, sender_id);

ALTER TABLE contacts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_contacts ON contacts
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- ─── Automation Rules ─────────────────────────────────────────────────────────
-- A rule belongs to one tenant + one IG account.
-- trigger_type: WELCOME | KEYWORD | FALLBACK
-- execution_mode: FIRST_MATCH | RUN_ALL
CREATE TABLE automation_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID            NOT NULL,
    ig_account_id   VARCHAR(255)    NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    trigger_type    VARCHAR(50)     NOT NULL,   -- WELCOME | KEYWORD | FALLBACK
    execution_mode  VARCHAR(50)     NOT NULL DEFAULT 'FIRST_MATCH',
    reply_text      TEXT            NOT NULL,
    priority        INT             NOT NULL DEFAULT 100, -- lower = higher priority
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    cooldown_seconds BIGINT         NOT NULL DEFAULT 3600, -- 0 = no cooldown
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_rules_tenant_account_active
    ON automation_rules (tenant_id, ig_account_id, active, priority);

ALTER TABLE automation_rules ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_rules ON automation_rules
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE TRIGGER trg_rules_updated_at
    BEFORE UPDATE ON automation_rules
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

-- ─── Rule Keywords ───────────────────────────────────────────────────────────
-- A KEYWORD rule can have multiple trigger keywords (OR logic: any keyword matches).
-- Stored normalised (trimmed, lower-cased) to keep matching simple.
CREATE TABLE rule_keywords (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
    keyword VARCHAR(255) NOT NULL,
    CONSTRAINT uq_rule_keyword UNIQUE (rule_id, keyword)
);

CREATE INDEX idx_rule_keywords_rule ON rule_keywords (rule_id);

-- ─── Automation Execution Log ─────────────────────────────────────────────────
-- Idempotency guard + audit trail.
-- (tenant, ig_account, sender, rule) within cooldown window = reject duplicate.
CREATE TABLE automation_execution_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID            NOT NULL,
    ig_account_id   VARCHAR(255)    NOT NULL,
    sender_id       VARCHAR(255)    NOT NULL,
    rule_id         UUID            NOT NULL,
    message_id      VARCHAR(255),               -- mid used to trigger this
    executed_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    status          VARCHAR(50)     NOT NULL DEFAULT 'SENT' -- SENT | SKIPPED | FAILED
);

-- Idempotency check: did this rule already fire for this sender recently?
CREATE INDEX idx_exec_log_cooldown
    ON automation_execution_log (tenant_id, ig_account_id, sender_id, rule_id, executed_at DESC);

ALTER TABLE automation_execution_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_exec_log ON automation_execution_log
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

COMMENT ON TABLE contacts IS
    'Per-tenant contact registry. first_seen_at drives the one-time Welcome message trigger.';
COMMENT ON TABLE automation_rules IS
    'Automation rules per tenant × IG account. Loaded into in-memory cache at startup.';
COMMENT ON TABLE rule_keywords IS
    'Normalised keyword tokens for KEYWORD-type automation rules.';
COMMENT ON TABLE automation_execution_log IS
    'Audit + idempotency log for automation rule executions.';
