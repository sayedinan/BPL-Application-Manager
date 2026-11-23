-- No PRAGMA here: PRAGMA foreign_keys is a no-op inside Flyway's transaction. The JDBC URL sets it.

CREATE TABLE users (
    id                   TEXT    PRIMARY KEY,                       -- UUID, 36 chars
    username             TEXT    NOT NULL,
    full_name            TEXT,
    password_hash        TEXT    NOT NULL,
    role                 TEXT    NOT NULL,   -- SYS_ADMIN | ADMIN | USER; enum CHECK dropped (a new role would need a
                                             -- table rebuild in SQLite); enforced by the Java enum (Phase 3 step 7)
    email                TEXT,
    phone_number         TEXT    CHECK (phone_number IS NULL OR
                                  (length(phone_number) = 13 AND
                                   phone_number GLOB '8801[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]')),
    must_change_password INTEGER NOT NULL DEFAULT 1 CHECK (must_change_password IN (0,1)),
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_users_username ON users(username);

CREATE TABLE applications (
    id                              INTEGER PRIMARY KEY AUTOINCREMENT,   -- AUTOINCREMENT: ids never reused (H3)
    name                            TEXT    NOT NULL,
    server_ip                       TEXT    NOT NULL,
    ssh_username                    TEXT    NOT NULL,
    ssh_password_enc                TEXT    NOT NULL,
    ssh_host_key_fingerprint        TEXT    NOT NULL,
    start_script                    TEXT    NOT NULL,
    stop_script                     TEXT    NOT NULL,
    log_script                      TEXT    NOT NULL,
    group_name                      TEXT,
    email_start_message             TEXT,
    email_stop_message              TEXT,
    email_external_online_message   TEXT,
    email_external_offline_message  TEXT,
    sms_start_message               TEXT CHECK (sms_start_message IS NULL OR length(sms_start_message) <= 160),
    sms_stop_message                TEXT CHECK (sms_stop_message IS NULL OR length(sms_stop_message) <= 160),
    sms_external_online_message     TEXT CHECK (sms_external_online_message IS NULL OR length(sms_external_online_message) <= 160),
    sms_external_offline_message    TEXT CHECK (sms_external_offline_message IS NULL OR length(sms_external_offline_message) <= 160),
    -- no deleted_at: V2's header records it as a deliberate deviation and no Java code maps it (hard delete)
    created_at                      INTEGER NOT NULL,
    updated_at                      INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_applications_name_lower ON applications(lower(name));

CREATE TABLE user_application_assignments (
    user_id        TEXT    NOT NULL REFERENCES users(id)        ON DELETE CASCADE,
    application_id INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    assigned_at    INTEGER NOT NULL,
    PRIMARY KEY (user_id, application_id)
);

CREATE TABLE audit_logs (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp               INTEGER NOT NULL,
    actor_username          TEXT    NOT NULL,
    actor_role              TEXT    NOT NULL,
    action_type             TEXT    NOT NULL,   -- enum CHECK intentionally dropped: V10/V13 each changed it,
                                                -- and every change would need a full table rebuild in SQLite.
                                                -- The Java enum/validator is the guard.
    target_application_id   INTEGER,            -- no FK, never reused
    target_application_name TEXT,
    target_user_id          TEXT,
    detail                  TEXT CHECK (detail IS NULL OR json_valid(detail)),
    result                  TEXT    NOT NULL CHECK (result IN ('SUCCESS','FAILURE')),
    source_ip               TEXT
);
CREATE INDEX idx_audit_timestamp   ON audit_logs(timestamp DESC);
CREATE INDEX idx_audit_actor       ON audit_logs(actor_username);
CREATE INDEX idx_audit_application ON audit_logs(target_application_id);
-- Optional hardening (recommended): make "insert-only" a DB guarantee. Side effects: test cleanup cannot
-- use deleteAll() on audit_logs, and any future retention purge must drop these triggers in its migration.
CREATE TRIGGER audit_logs_no_update BEFORE UPDATE ON audit_logs
BEGIN SELECT RAISE(ABORT, 'audit_logs is insert-only'); END;
CREATE TRIGGER audit_logs_no_delete BEFORE DELETE ON audit_logs
BEGIN SELECT RAISE(ABORT, 'audit_logs is insert-only'); END;

CREATE TABLE application_log_lines (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    line_number    INTEGER NOT NULL,
    content        TEXT    NOT NULL,
    captured_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_application_log_unique ON application_log_lines(application_id, line_number);
CREATE INDEX        idx_application_log_latest ON application_log_lines(application_id, captured_at DESC);

CREATE TABLE idempotency_keys (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    idempotency_key TEXT    NOT NULL,
    user_id         TEXT    NOT NULL REFERENCES users(id)        ON DELETE CASCADE,
    application_id  INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    created_at      INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL            -- was DEFAULT now()+24h; entity/service must set it (check IdempotencyService)
);
CREATE UNIQUE INDEX idx_idempotency_unique ON idempotency_keys(user_id, application_id, idempotency_key);
CREATE INDEX        idx_idempotency_expiry ON idempotency_keys(expires_at);

CREATE TABLE websocket_connections (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   TEXT    NOT NULL,
    username     TEXT    NOT NULL,
    connected_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_ws_connections_session  ON websocket_connections(session_id);
CREATE INDEX        idx_ws_connections_username ON websocket_connections(username);

CREATE TABLE websocket_subscriptions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT    NOT NULL,
    subscription_id TEXT    NOT NULL,
    topic_key       TEXT    NOT NULL,
    subscribed_at   INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_ws_subscriptions_session_sub ON websocket_subscriptions(session_id, subscription_id);
CREATE INDEX        idx_ws_subscriptions_topic       ON websocket_subscriptions(topic_key);

CREATE TABLE application_status_transitions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id  INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    online          INTEGER NOT NULL CHECK (online IN (0,1)),
    transitioned_at INTEGER NOT NULL
);
CREATE INDEX idx_status_transitions_app ON application_status_transitions(application_id, transitioned_at DESC);

CREATE TABLE application_status_streak (
    application_id    INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    online            INTEGER NOT NULL CHECK (online IN (0,1)),
    streak_started_at INTEGER NOT NULL
);

CREATE TABLE sms_send_log (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id         INTEGER REFERENCES applications(id) ON DELETE SET NULL,   -- H7 resolved: SMS history must not block app delete
    recipient_phone_number TEXT    NOT NULL,
    event_type             TEXT    NOT NULL,
    is_error               INTEGER NOT NULL CHECK (is_error IN (0,1)),
    inserted_sms_id        TEXT,
    response_message       TEXT,
    sent_at                INTEGER NOT NULL
);
CREATE INDEX idx_sms_send_log_application_id ON sms_send_log(application_id);
CREATE INDEX idx_sms_send_log_sent_at        ON sms_send_log(sent_at);

CREATE TABLE application_health_config (
    application_id        INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
    url                   TEXT    NOT NULL CHECK (url LIKE 'http://%' OR url LIKE 'https://%'),
    api_key_enc           TEXT,
    tls_pin_sha256        TEXT    CHECK (tls_pin_sha256 IS NULL OR
                                    (length(tls_pin_sha256) = 64 AND tls_pin_sha256 NOT GLOB '*[^0-9a-f]*')),
    poll_interval_seconds INTEGER NOT NULL DEFAULT 10 CHECK (poll_interval_seconds BETWEEN 3 AND 300),
    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL,
    CONSTRAINT chk_health_config_pin_needs_https CHECK (tls_pin_sha256 IS NULL OR url LIKE 'https://%')
);

CREATE TABLE application_health_latest (
    application_id       INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    checked_at           INTEGER NOT NULL,
    reachable            INTEGER NOT NULL CHECK (reachable IN (0,1)),
    http_status          INTEGER,
    response_ms          INTEGER CHECK (response_ms >= 0),
    status               TEXT    CHECK (status IN ('UP','DEGRADED','DOWN','UNKNOWN')),
    payload              TEXT    CHECK (payload IS NULL OR json_valid(payload)),
    error                TEXT,
    ssl_not_after        INTEGER,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0)
);

CREATE TABLE application_health_history (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    checked_at     INTEGER NOT NULL,
    reachable      INTEGER NOT NULL CHECK (reachable IN (0,1)),
    response_ms    INTEGER CHECK (response_ms >= 0),
    status         TEXT    CHECK (status IN ('UP','DEGRADED','DOWN','UNKNOWN')),
    cpu_percent    REAL    CHECK (cpu_percent    BETWEEN 0 AND 100),
    memory_percent REAL    CHECK (memory_percent BETWEEN 0 AND 100),
    disk_percent   REAL    CHECK (disk_percent   BETWEEN 0 AND 100)
);
CREATE INDEX idx_health_history_app ON application_health_history(application_id, checked_at DESC);

CREATE TABLE application_maintenance (
    application_id INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    starts_at      INTEGER NOT NULL,
    ends_at        INTEGER NOT NULL,
    note           TEXT    CHECK (note IS NULL OR length(note) <= 200),
    started_by     TEXT    NOT NULL,
    started_at     INTEGER NOT NULL,
    CONSTRAINT application_maintenance_window_order CHECK (ends_at > starts_at)
);

-- Spring Session JDBC (V6). Confirm first whether the jar ships schema-sqlite.sql; prefer it if it exists:
--   unzip -p ~/.gradle/caches/**/spring-session-jdbc-*.jar org/springframework/session/jdbc/schema-sqlite.sql
-- Otherwise this adaptation (BYTEA -> BLOB):
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            TEXT    NOT NULL PRIMARY KEY,
    SESSION_ID            TEXT    NOT NULL,
    CREATION_TIME         INTEGER NOT NULL,
    LAST_ACCESS_TIME      INTEGER NOT NULL,
    MAX_INACTIVE_INTERVAL INTEGER NOT NULL,
    EXPIRY_TIME           INTEGER NOT NULL,
    PRINCIPAL_NAME        TEXT
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX        SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX        SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID TEXT NOT NULL REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE,
    ATTRIBUTE_NAME     TEXT NOT NULL,
    ATTRIBUTE_BYTES    BLOB NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
);
