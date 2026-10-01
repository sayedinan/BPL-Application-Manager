-- V19 - users get UUID ids; users are hard-deleted.
-- Existing rows keep their data; each user just gets a new UUID.

-- 1. new UUID id on users
ALTER TABLE users ADD COLUMN uuid_id UUID NOT NULL DEFAULT gen_random_uuid();

-- 2. carry it into every table that points at users
ALTER TABLE user_application_assignments ADD COLUMN user_uuid UUID;
UPDATE user_application_assignments a SET user_uuid = u.uuid_id FROM users u WHERE a.user_id = u.id;

ALTER TABLE idempotency_keys ADD COLUMN user_uuid UUID;
UPDATE idempotency_keys k SET user_uuid = u.uuid_id FROM users u WHERE k.user_id = u.id;

ALTER TABLE audit_logs ADD COLUMN target_user_uuid UUID;
UPDATE audit_logs l SET target_user_uuid = u.uuid_id FROM users u WHERE l.target_user_id = u.id;

-- 3. drop the old bigint columns (this also drops their PK/FK/unique index)
ALTER TABLE user_application_assignments DROP COLUMN user_id;
ALTER TABLE idempotency_keys DROP COLUMN user_id;
ALTER TABLE audit_logs DROP COLUMN target_user_id;
ALTER TABLE users DROP COLUMN id;

-- 4. rename the new columns into place
ALTER TABLE users RENAME COLUMN uuid_id TO id;
ALTER TABLE users ADD PRIMARY KEY (id);

ALTER TABLE user_application_assignments RENAME COLUMN user_uuid TO user_id;
ALTER TABLE user_application_assignments ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE idempotency_keys RENAME COLUMN user_uuid TO user_id;
ALTER TABLE idempotency_keys ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE audit_logs RENAME COLUMN target_user_uuid TO target_user_id;

-- 5. restore keys; both child tables now cascade on user delete
ALTER TABLE user_application_assignments ADD PRIMARY KEY (user_id, application_id);
ALTER TABLE user_application_assignments
    ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE idempotency_keys
    ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX idx_idempotency_unique ON idempotency_keys(user_id, application_id, idempotency_key);

-- 6. hard delete: purge old soft-deleted rows, username unique outright
DELETE FROM users WHERE deleted_at IS NOT NULL;
DROP INDEX idx_users_username_active;
CREATE UNIQUE INDEX idx_users_username ON users(username);
