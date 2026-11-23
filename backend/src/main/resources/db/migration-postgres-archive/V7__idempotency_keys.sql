-- V7 — idempotency_keys table.
--
-- Source of truth: SPEC.md §3.1 (idempotency_keys).
--
-- Backs the `Idempotency-Key` header on POST /applications/{id}/start
-- and /stop (SPEC §4.1, §4.2 IDEMPOTENCY_CONFLICT 409). A duplicate
-- (user, application, key) tuple within the 24h window is the conflict
-- signal; an INSERT that lands after expiry is fine (the old row is
-- cleaned up opportunistically — see header comment below).
--
-- ON DELETE behavior:
--   * application_id: ON DELETE CASCADE. Applications are hard-deleted
--     (SPEC §3.3); their idempotency keys must follow.
--   * user_id: NO cascade. Users are soft-deleted (users.deleted_at),
--     so the FK row stays. Soft-deleted users can no longer hit any
--     endpoint, so the orphaned keys are inert and expire in 24h
--     anyway. There is no application-layer cleanup needed.
--
-- `expires_at` defaults to `NOW() + INTERVAL '24 hours'` at the DB
-- level, so the TTL is enforced even if application code forgets.
-- Expired rows are deleted opportunistically on INSERT / SELECT
-- (WHERE expires_at < NOW()) per SPEC §3.1 — no scheduled job, no
-- Spring @Scheduled annotation. This keeps the system single-JAR-
-- simple (SPEC §1).
--
-- The two indexes match the SPEC:
--   * idx_idempotency_unique on (user_id, application_id, idempotency_key)
--     — prevents duplicate-key inserts from succeeding, which is the
--     409 IDEMPOTENCY_CONFLICT path.
--   * idx_idempotency_expiry on (expires_at)
--     — supports the opportunistic cleanup query.

CREATE TABLE idempotency_keys (
    id             BIGSERIAL    PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id        BIGINT       NOT NULL REFERENCES users(id),
    application_id BIGINT       NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ  NOT NULL DEFAULT (NOW() + INTERVAL '24 hours')
);

CREATE UNIQUE INDEX idx_idempotency_unique ON idempotency_keys(user_id, application_id, idempotency_key);
CREATE INDEX        idx_idempotency_expiry ON idempotency_keys(expires_at);
