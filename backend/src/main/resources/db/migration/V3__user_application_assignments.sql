-- V3 — user_application_assignments join table.
--
-- Source of truth: SPEC.md §3.1 (user_application_assignments).
--
-- ON DELETE CASCADE behavior:
--   * applications: hard delete (SPEC §3.3) → cascade fires; assignment
--     rows are removed automatically when an application is deleted.
--     This is the SPEC's intended behavior.
--   * users: soft delete via `users.deleted_at` → cascade does NOT fire
--     (the user row is still present). Soft-deleted users therefore
--     leave assignment rows behind, referencing a user who can no
--     longer log in. This is harmless: the user id never collides
--     with a new account (BIGSERIAL), and no application code reads
--     these orphaned rows because user lookups always filter
--     `WHERE deleted_at IS NULL`. This is a known side effect of
--     the soft-delete model, not something to fix in the schema.
--
-- No additional indexes: the composite PK on (user_id, application_id)
-- covers lookups in both directions, which is all the application
-- queries against this table will need.

CREATE TABLE user_application_assignments (
    user_id        BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    application_id BIGINT      NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    assigned_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, application_id)
);
