-- V19 - users are hard-deleted now.
ALTER TABLE idempotency_keys DROP CONSTRAINT IF EXISTS idempotency_keys_user_id_fkey;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- Purge any old soft-deleted rows, then make username unique outright.
DELETE FROM users WHERE deleted_at IS NOT NULL;
DROP INDEX IF EXISTS idx_users_username_active;
CREATE UNIQUE INDEX idx_users_username ON users(username);
