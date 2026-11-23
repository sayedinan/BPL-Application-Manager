-- V4 — audit_logs table.
--
-- Source of truth: SPEC.md §3.1 (audit_logs) and §3.2 (locked action
-- type enum, including SHELLCHECK_UNAVAILABLE per the v1.1 update).
--
-- Two intentional additions beyond §3.1's literal text, both flagged:
--
-- 1. CHECK constraint on action_type pinning the §3.2 enum at the
--    schema level. SPEC §3.1 defines the column as plain VARCHAR(32)
--    NOT NULL, but §3.2 lists 14 locked values. Without a DB-side
--    check, a typo or stale code path can insert an unknown value and
--    silently corrupt the audit log. The trade-off is that adding a
--    new action type requires a migration (which is the right cadence
--    anyway for an audit log).
--
-- 2. Foreign-key-free `target_application_id` per SPEC §3.1 and §3.3
--    "No FK, never reused" — application IDs are never reused, so the
--    numeric value remains a safe permanent reference even after the
--    referenced application row is hard-deleted. The plain
--    `target_application_name` snapshot is the human-readable companion.
--
-- The three indexes match SPEC §3.1:
--   * idx_audit_timestamp — descending, supports the date-filtered
--     paginated read endpoint (SPEC §4.3 GET /audit-logs)
--   * idx_audit_actor — btree on actor_username, supports the
--     "what did this user do" filter
--   * idx_audit_application — btree on target_application_id (no FK
--     present, so this is a query-only index), supports the
--     "history for application X" filter

CREATE TABLE audit_logs (
    id                          BIGSERIAL    PRIMARY KEY,
    timestamp                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_username              VARCHAR(64)  NOT NULL,
    actor_role                  VARCHAR(16)  NOT NULL,
    action_type                 VARCHAR(32)  NOT NULL
                                  CHECK (action_type IN (
                                    'START_APPLICATION',
                                    'STOP_APPLICATION',
                                    'CREATE_APPLICATION',
                                    'UPDATE_APPLICATION',
                                    'DELETE_APPLICATION',
                                    'ASSIGN_APPLICATION',
                                    'RESET_PASSWORD',
                                    'CHANGE_PASSWORD',
                                    'CREATE_USER',
                                    'DELETE_USER',
                                    'CREATE_ADMIN',
                                    'LOGIN',
                                    'LOGOUT',
                                    'SHELLCHECK_UNAVAILABLE'
                                  )),
    target_application_id       BIGINT,                                -- No FK, never reused
    target_application_name     VARCHAR(64),                           -- Snapshot for readability
    target_user_id              BIGINT,
    detail                      JSONB,
    result                      VARCHAR(16)  NOT NULL CHECK (result IN ('SUCCESS','FAILURE'))
);

CREATE INDEX idx_audit_timestamp   ON audit_logs(timestamp DESC);
CREATE INDEX idx_audit_actor       ON audit_logs(actor_username);
CREATE INDEX idx_audit_application ON audit_logs(target_application_id);
