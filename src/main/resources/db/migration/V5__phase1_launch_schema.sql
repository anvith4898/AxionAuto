ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'OWNER';

UPDATE users
SET role = 'OWNER'
WHERE role IS NULL;

ALTER TABLE users
    ADD CONSTRAINT chk_users_role
        CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'));

ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_contacts_last_read
    ON contacts (tenant_id, ig_account_id, sender_id, last_read_at);
