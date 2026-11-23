-- V1 — users table only.
--
-- Source of truth: SPEC.md §3.1 (users). Subsequent migrations will
-- add applications (V2), user_application_assignments (V3), and so on.
-- Idempotent against an empty database only — Flyway tracks the
-- applied version in flyway_schema_history.

CREATE TABLE users (
    id                    BIGSERIAL    PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,        -- bcrypt
    role                  VARCHAR(16)  NOT NULL CHECK (role IN ('SYS_ADMIN','ADMIN','USER')),
    must_change_password  BOOLEAN      NOT NULL DEFAULT true,
    deleted_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial unique index: username is unique only among non-deleted rows,
-- so a soft-deleted user with the same name doesn't block re-creation.
CREATE UNIQUE INDEX idx_users_username_active ON users(username) WHERE deleted_at IS NULL;
