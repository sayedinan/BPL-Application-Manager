-- V2 — applications table.
--
-- Source of truth: SPEC.md §3.1 (applications).
--
-- DEVIATION FROM SPEC §3.3:
-- SPEC §3.3 states "Hard delete: applications has no deleted_at
-- column — DELETE removes the row". A `deleted_at TIMESTAMPTZ` column
-- is added here per explicit instruction, so the table can be soft-
-- deleted if needed. Combined with the functional unique index on
-- LOWER(name) (see below), this means a soft-deleted application name
-- still blocks reuse at the DB layer — the index sees LOWER(name)
-- regardless of deleted_at. This is a known trade-off of the
-- deviation and should be revisited if/when the SPEC is updated to
-- formalize soft delete for applications.
--
-- CASE-INSENSITIVE UNIQUENESS (SPEC §3.3):
-- SPEC §3.3 says "Application names: ^[a-zA-Z0-9_-]{1,64}$, case-
-- insensitive unique". PostgreSQL's default UNIQUE is case-sensitive,
-- so a plain UNIQUE on name would let "Foo" and "foo" coexist. The
-- constraint is satisfied here with a functional unique index on
-- LOWER(name). Application code is still expected to validate the
-- regex on input, but the case-insensitive uniqueness guarantee is
-- enforced at the DB layer, not in application code.
--
-- Subsequent migrations will add user_application_assignments (V3),
-- audit_logs, application_log_lines, and idempotency_keys.

CREATE TABLE applications (
    id                          BIGSERIAL    PRIMARY KEY,
    name                        VARCHAR(64)  NOT NULL,
    server_ip                   INET         NOT NULL,
    ssh_username                VARCHAR(64)  NOT NULL,
    ssh_password_enc            TEXT         NOT NULL,   -- Jasypt AES-256-GCM
    ssh_host_key_fingerprint    VARCHAR(64)  NOT NULL,   -- SHA256
    start_script                TEXT         NOT NULL,
    stop_script                 TEXT         NOT NULL,
    log_script                  TEXT         NOT NULL,
    poll_interval_seconds       INT          NOT NULL DEFAULT 5,
    status                      VARCHAR(16)  NOT NULL DEFAULT 'STOPPED'
                                  CHECK (status IN ('RUNNING','STOPPED','STARTING','STOPPING','ERROR')),
    started_at                  TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,             -- DEVIATION: see header
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- SPEC §3.3 case-insensitive uniqueness. The functional index on
-- LOWER(name) treats 'Foo' and 'foo' as the same key, satisfying the
-- SPEC's "case-insensitive unique" requirement at the DB layer.
CREATE UNIQUE INDEX idx_applications_name_lower ON applications(LOWER(name));
