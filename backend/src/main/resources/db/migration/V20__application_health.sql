-- V20 - application health monitoring tables.
--
-- Source of truth: HEALTH-MONITORING.md §4.
--
-- Three tables, three different jobs:
--
--   * application_health_config  - optional, one row per application.
--     No row means "no health monitoring": that application keeps being
--     checked by the SSH status_script poller (HEALTH-MONITORING.md D3).
--
--   * application_health_latest  - one row per application, overwritten
--     on every poll. This is what the dashboard health card reads, so it
--     is a single-row lookup instead of a scan of the history table.
--
--   * application_health_history - compact, insert-only, one row per
--     poll (no payload, so it stays small). Retention is 30 days,
--     enforced by a scheduled cleanup job in application code, not here.
--
-- All three cascade-delete with the application (same pattern as V5 and
-- V12): this is monitoring data about a specific application, not a
-- permanent record like audit_logs.
--
-- API KEY STORAGE (HEALTH-MONITORING.md D2):
-- api_key_enc holds ciphertext from SshCredentialCipher (AES-256-GCM,
-- hex-encoded), the same way applications.ssh_password_enc does. It is
-- never returned by any endpoint. NULL means the application's health
-- endpoint needs no key.
--
-- TLS PINNING (HEALTH-MONITORING.md D6):
-- tls_pin_sha256 is the lowercase hex SHA-256 of the application's
-- certificate, for HTTPS endpoints that use a self-signed certificate.
-- NULL means normal certificate verification. There is deliberately no
-- "skip verification" setting.

CREATE TABLE application_health_config (
    application_id         BIGINT        PRIMARY KEY
                                           REFERENCES applications(id) ON DELETE CASCADE,
    enabled                BOOLEAN       NOT NULL DEFAULT TRUE,
    url                    TEXT          NOT NULL
                                           CHECK (url ~* '^https?://'),
    format                 VARCHAR(16)   NOT NULL
                                           CHECK (format IN ('CONTRACT', 'ACTUATOR')),
    api_key_enc            TEXT,
    tls_pin_sha256         VARCHAR(64)
                                           CHECK (tls_pin_sha256 ~ '^[0-9a-f]{64}$'),
    poll_interval_seconds  INT           NOT NULL DEFAULT 10
                                           CHECK (poll_interval_seconds BETWEEN 3 AND 300),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- A pinned fingerprint only makes sense for an HTTPS URL.
    CONSTRAINT chk_health_config_pin_needs_https
        CHECK (tls_pin_sha256 IS NULL OR url ~* '^https://')
);

CREATE TABLE application_health_latest (
    application_id         BIGINT        PRIMARY KEY
                                           REFERENCES applications(id) ON DELETE CASCADE,
    checked_at             TIMESTAMPTZ   NOT NULL,
    reachable              BOOLEAN       NOT NULL,
    http_status            INT,
    response_ms            INT           CHECK (response_ms >= 0),
    status                 VARCHAR(16)
                                           CHECK (status IN ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN')),
    payload                JSONB,        -- last good response, normalized to the contract shape
    error                  TEXT,         -- short reason when the check failed
    ssl_not_after          TIMESTAMPTZ,  -- certificate expiry, HTTPS only
    consecutive_failures   INT           NOT NULL DEFAULT 0
                                           CHECK (consecutive_failures >= 0)
);

CREATE TABLE application_health_history (
    id                     BIGSERIAL     PRIMARY KEY,
    application_id         BIGINT        NOT NULL
                                           REFERENCES applications(id) ON DELETE CASCADE,
    checked_at             TIMESTAMPTZ   NOT NULL,
    reachable              BOOLEAN       NOT NULL,
    response_ms            INT           CHECK (response_ms >= 0),
    status                 VARCHAR(16)
                                           CHECK (status IN ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN'))
);

CREATE INDEX idx_health_history_app
    ON application_health_history(application_id, checked_at DESC);
