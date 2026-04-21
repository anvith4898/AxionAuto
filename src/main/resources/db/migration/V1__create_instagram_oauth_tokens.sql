-- V1: Instagram OAuth Token table
-- Multi-tenant, encrypted token storage for Instagram Business accounts
-- Aligns with the oauth_tokens schema in the platform architecture doc

CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- for gen_random_uuid()

-- ─── Instagram OAuth Tokens ────────────────────────────────────────────────────
CREATE TABLE instagram_oauth_tokens (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Multi-tenancy
    tenant_id               UUID NOT NULL,
    user_id                 VARCHAR(255) NOT NULL,   -- platform user who connected the account

    -- Instagram identifiers
    instagram_account_id    VARCHAR(255),            -- IG Business Account ID (from Graph API)
    instagram_username      VARCHAR(255),            -- display name / username

    -- Encrypted token storage (AES-256-GCM)
    access_token_encrypted  BYTEA NOT NULL,          -- ciphertext bytes
    access_token_iv         BYTEA NOT NULL,          -- 12-byte GCM nonce
    access_token_tag        BYTEA NOT NULL,          -- 16-byte GCM authentication tag

    -- Token metadata
    token_expiry            TIMESTAMPTZ NOT NULL,    -- when token expires (Meta: 60 days)
    scope                   TEXT,                    -- space-separated granted scopes
    token_type              VARCHAR(50) DEFAULT 'bearer',

    -- Lifecycle
    connected_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_refreshed_at       TIMESTAMPTZ,
    refresh_attempts        INT NOT NULL DEFAULT 0,
    status                  VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | EXPIRED | REFRESH_FAILED | REVOKED

    -- Audit
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT uq_tenant_ig_account UNIQUE (tenant_id, instagram_account_id),
    CONSTRAINT uq_tenant_user        UNIQUE (tenant_id, user_id)
);

-- Row-Level Security — enforced at DB level for multi-tenant isolation
ALTER TABLE instagram_oauth_tokens ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON instagram_oauth_tokens
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Indexes
CREATE INDEX idx_ig_tokens_tenant          ON instagram_oauth_tokens(tenant_id);
CREATE INDEX idx_ig_tokens_tenant_status   ON instagram_oauth_tokens(tenant_id, status);
CREATE INDEX idx_ig_tokens_expiry          ON instagram_oauth_tokens(token_expiry) WHERE status = 'ACTIVE';
CREATE INDEX idx_ig_tokens_ig_account      ON instagram_oauth_tokens(instagram_account_id);

-- Auto-update updated_at on row change
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER trg_ig_tokens_updated_at
    BEFORE UPDATE ON instagram_oauth_tokens
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

COMMENT ON TABLE instagram_oauth_tokens IS
    'Encrypted Instagram Business OAuth tokens. One row per tenant×user connection. '
    'Tokens are AES-256-GCM encrypted; keys managed externally (Vault / env var).';

COMMENT ON COLUMN instagram_oauth_tokens.access_token_encrypted IS 'AES-256-GCM ciphertext of the long-lived Meta access token';
COMMENT ON COLUMN instagram_oauth_tokens.access_token_iv        IS '12-byte GCM initialization vector (unique per encryption operation)';
COMMENT ON COLUMN instagram_oauth_tokens.access_token_tag       IS '16-byte GCM authentication tag for tamper detection';
COMMENT ON COLUMN instagram_oauth_tokens.token_expiry           IS 'Meta long-lived tokens expire in ~60 days; proactive refresh at T-10min';
