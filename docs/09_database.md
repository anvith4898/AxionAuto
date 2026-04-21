# Database Schema

## Technology

- **RDBMS**: PostgreSQL 15+
- **Migrations**: Flyway (versioned, `V1__` → `V4__`)
- **ORM**: Hibernate 6 / Spring Data JPA (`ddl-auto: validate` — schema managed exclusively by Flyway)
- **Multi-tenancy**: PostgreSQL Row-Level Security (RLS) on every table
- **Indexing**: Compound indexes on all hot-path queries; GIN indexes for JSONB

---

## Migration History

| Version | File | Tables Created/Modified |
|---|---|---|
| V1 | `V1__create_instagram_oauth_tokens.sql` | `instagram_oauth_tokens`; defines `update_updated_at_column()` trigger function |
| V2 | `V2__create_webhook_events_table.sql` | `instagram_webhook_events` |
| V3 | `V3__create_automation_engine_tables.sql` | `automation_rules`, `rule_keywords`, `contacts`, `automation_execution_log` |
| V4 | `V4__production_schema.sql` | NEW: `users`, `instagram_accounts`, `messages`; retrofit of all V1-V3 tables with tenant_id, FKs, RLS, JSONB |

---

## Schema Overview

```
users (tenant anchor)
  └─◄── instagram_oauth_tokens  (1 token per connected IG account)
  └─◄── instagram_accounts       (canonical connected account registry)
              └─◄── contacts                (one per IGSID × account)
              └─◄── automation_rules        (one per rule)
                          └─◄── rule_keywords  (M keywords per rule)
  └─◄── messages                 (inbound + outbound log)
  └─◄── instagram_webhook_events (raw deduped webhook payloads)
  └─◄── automation_execution_log (per-rule result per message)
```

---

## Table: `users`

Tenant anchor. One row per SaaS customer account.

```sql
CREATE TABLE users (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL UNIQUE,     -- shared discriminator
    email        VARCHAR(320) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    plan         VARCHAR(50)  NOT NULL DEFAULT 'FREE',
    -- FREE | STARTER | GROWTH | ENTERPRISE
    status       VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | SUSPENDED | DELETED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

**RLS:** `tenant_id = current_setting('app.current_tenant')::uuid`

---

## Table: `instagram_oauth_tokens`

AES-256-GCM encrypted access tokens for connected Instagram Business Accounts.

```sql
CREATE TABLE instagram_oauth_tokens (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID        NOT NULL,
    user_id                VARCHAR(255) NOT NULL,
    instagram_account_id   VARCHAR(255),
    instagram_username     VARCHAR(255),
    access_token_encrypted BYTEA       NOT NULL,   -- ciphertext
    access_token_iv        BYTEA       NOT NULL,   -- 12-byte GCM nonce
    access_token_tag       BYTEA       NOT NULL,   -- 16-byte auth tag
    token_expiry           TIMESTAMPTZ NOT NULL,
    scope                  TEXT,
    token_type             VARCHAR(50) DEFAULT 'bearer',
    connected_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_refreshed_at      TIMESTAMPTZ,
    refresh_attempts       INT         NOT NULL DEFAULT 0,
    status                 VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | EXPIRED | REFRESH_FAILED | REVOKED
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_tenant_ig_account UNIQUE (tenant_id, instagram_account_id),
    CONSTRAINT uq_tenant_user       UNIQUE (tenant_id, user_id)
);
```

**Indexes:**
- `idx_ig_tokens_tenant` on `(tenant_id)`
- `idx_ig_tokens_tenant_status` on `(tenant_id, status)`
- `idx_ig_tokens_expiry` on `(token_expiry) WHERE status = 'ACTIVE'`
- `idx_ig_tokens_ig_account` on `(instagram_account_id)` → used by `resolveTenant()`

**RLS:** `tenant_id = current_setting('app.current_tenant')::uuid`

---

## Table: `instagram_accounts`

Canonical registry of connected Instagram Business Accounts (promoted from embedded columns in V4).

```sql
CREATE TABLE instagram_accounts (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES users(tenant_id) ON DELETE CASCADE,
    ig_account_id  VARCHAR(255) NOT NULL,
    ig_username    VARCHAR(255),
    page_id        VARCHAR(255),             -- linked Facebook Page
    token_row_id   UUID REFERENCES instagram_oauth_tokens(id) ON DELETE SET NULL,
    connected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    status         VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | DISCONNECTED | TOKEN_EXPIRED
    metadata       JSONB       NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_ig_account_per_tenant UNIQUE (tenant_id, ig_account_id)
);
```

**Indexes:**
- `idx_ig_accounts_tenant_active` on `(tenant_id, status) WHERE status = 'ACTIVE'`
- `idx_ig_accounts_metadata` GIN on `metadata` for ad-hoc JSONB queries

---

## Table: `messages`

Canonical inbound and outbound message log. One row per Meta message.

```sql
CREATE TABLE messages (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES users(tenant_id) ON DELETE CASCADE,
    ig_account_id   VARCHAR(255) NOT NULL,
    direction       VARCHAR(20) NOT NULL,           -- INBOUND | OUTBOUND
    sender_id       VARCHAR(255) NOT NULL,
    recipient_id    VARCHAR(255) NOT NULL,
    message_type    VARCHAR(50) NOT NULL DEFAULT 'TEXT',
    message_text    TEXT,
    media_url       TEXT,
    raw_payload     JSONB       NOT NULL DEFAULT '{}',
    meta_message_id VARCHAR(255) NOT NULL,          -- idempotency key ("mid")
    thread_id       UUID,
    sent_at         TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_message_mid_account UNIQUE (ig_account_id, meta_message_id)
);
```

**Indexes:**
- `idx_messages_sender_created` on `(tenant_id, ig_account_id, sender_id, created_at DESC)` — conversation load
- `idx_messages_account_created` on `(tenant_id, ig_account_id, created_at DESC)` — inbox list
- `idx_messages_outbound` on `(tenant_id, ig_account_id, created_at DESC) WHERE direction = 'OUTBOUND'`
- `idx_messages_inbound_mid` on `(ig_account_id, meta_message_id) WHERE direction = 'INBOUND'` — idempotency
- `idx_messages_raw_payload` GIN on `raw_payload` — ad-hoc queries

---

## Table: `automation_rules`

```sql
CREATE TABLE automation_rules (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    ig_account_id  VARCHAR(255) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    trigger_type   VARCHAR(50) NOT NULL,   -- WELCOME | KEYWORD | FALLBACK
    execution_mode VARCHAR(50) NOT NULL DEFAULT 'FIRST_MATCH',
    reply_text     TEXT        NOT NULL,
    priority       INT         NOT NULL DEFAULT 100,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    cooldown_seconds BIGINT    NOT NULL DEFAULT 3600,
    config         JSONB       NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Indexes:**
- `idx_rules_tenant_account_active` on `(tenant_id, ig_account_id, active, priority)` — cache load
- `idx_rules_tenant_active_type` on `(tenant_id, active, trigger_type) WHERE active = TRUE`
- `idx_rules_config` GIN on `config`

**JSONB `config` schema by trigger type:**
```json
// KEYWORD: { "keywords": ["price", "cost"], "match_mode": "ANY" }
// WELCOME: { "delay_seconds": 0 }
// FALLBACK: { "max_fires_per_day": 1 }
```

---

## Table: `rule_keywords`

```sql
CREATE TABLE rule_keywords (
    id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id UUID        NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
    keyword VARCHAR(255) NOT NULL
);
```

Keywords are stored normalized (trimmed, lowercased) to match against the lowercased message tokens.

---

## Table: `contacts`

One row per unique sender IGSID per IG Business Account.

```sql
CREATE TABLE contacts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    ig_account_id   VARCHAR(255) NOT NULL,
    sender_id       VARCHAR(255) NOT NULL,
    first_seen_at   TIMESTAMPTZ NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL,
    message_count   BIGINT      NOT NULL DEFAULT 0,
    metadata        JSONB       NOT NULL DEFAULT '{}',

    CONSTRAINT uq_contact UNIQUE (tenant_id, ig_account_id, sender_id)
);
```

**`isFirstInteraction`**: determined in `ContactRepository.upsertContact()` — the contact is "new" when `message_count == 1` after the `ON CONFLICT DO UPDATE` UPSERT.

---

## Table: `automation_execution_log`

```sql
CREATE TABLE automation_execution_log (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    ig_account_id       VARCHAR(255) NOT NULL,
    sender_id           VARCHAR(255) NOT NULL,
    rule_id             UUID        NOT NULL,
    message_id          VARCHAR(255),
    status              VARCHAR(50) NOT NULL,   -- SENT | SKIPPED | FAILED
    executed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    outbound_message_id UUID REFERENCES messages(id) ON DELETE SET NULL
);
```

Used for:
1. **Cooldown enforcement** — `existsWithinCooldown()` query
2. **Audit** — operators can see why rules did or didn't fire

---

## Table: `instagram_webhook_events`

Raw deduplicated webhook payloads, used as the audit trail and DLQ replay source.

```sql
CREATE TABLE instagram_webhook_events (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id      VARCHAR(255) NOT NULL UNIQUE,  -- idempotency key
    payload       TEXT         NOT NULL,          -- original raw JSON
    status        VARCHAR(50)  NOT NULL DEFAULT 'RECEIVED',
    -- RECEIVED | PROCESSING | PROCESSED | FAILED | DLQ
    tenant_id     UUID,
    ig_account_id VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

**Key properties:**
- `event_id` has a `UNIQUE` constraint — forms the second layer of idempotency deduplication
- `payload` preserves the original raw JSON, enabling DLQ replay to pass the real payload back to the consumer
- RLS is nullable-safe: events ingested before tenant resolution carry a sentinel UUID

---

## Row-Level Security Pattern

Applied to every table:

```sql
ALTER TABLE {table} ENABLE ROW LEVEL SECURITY;

CREATE POLICY rls_{table} ON {table}
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
```

The application sets the session variable before any query:
```sql
SET LOCAL app.current_tenant = '{tenantId}';
```

This ensures even if application-level tenant filtering is incomplete, the DB enforces isolation.

---

## Shared `updated_at` Trigger

Defined once in V1, applied to all tables:

```sql
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE 'plpgsql';

-- Applied per table:
CREATE TRIGGER trg_TABLE_updated_at
    BEFORE UPDATE ON TABLE
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
```
