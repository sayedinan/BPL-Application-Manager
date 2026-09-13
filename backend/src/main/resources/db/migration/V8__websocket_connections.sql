-- V8 — websocket_connections and websocket_subscriptions tables.
--
-- Source of truth: SPEC.md §5.4 / §7.1 (WebSocket connection & topic
-- subscription limits) and WebSocketAccessInterceptor, which is the
-- sole reader/writer of these two tables.
--
-- websocket_connections tracks one row per live STOMP session
-- (INSERT on CONNECT, DELETE on DISCONNECT). Used to enforce
-- MAX_GLOBAL / MAX_PER_USER connection limits.
--
-- websocket_subscriptions tracks one row per active topic
-- subscription within a session (INSERT on SUBSCRIBE, DELETE on
-- UNSUBSCRIBE/DISCONNECT). topic_key is either the literal
-- "audit-log" or an application id (as text) for
-- /topic/application-logs/{id}, and backs the MAX_PER_APP
-- subscriber-count query.
--
-- WebSocketAccessInterceptor.clearStaleStateOnStartup() runs
-- DELETE FROM on both tables in a @PostConstruct init method, so
-- both tables must exist before that bean initializes — this
-- migration must run before the app boots, which Flyway guarantees.
--
-- No FK to a "sessions" table: STOMP session ids are ephemeral and
-- not persisted elsewhere, so session_id is a plain VARCHAR here.

CREATE TABLE websocket_connections (
    id         BIGSERIAL    PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    username   VARCHAR(128) NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_ws_connections_session ON websocket_connections(session_id);
CREATE INDEX        idx_ws_connections_username ON websocket_connections(username);

CREATE TABLE websocket_subscriptions (
    id              BIGSERIAL    PRIMARY KEY,
    session_id      VARCHAR(128) NOT NULL,
    subscription_id VARCHAR(128) NOT NULL,
    topic_key       VARCHAR(128) NOT NULL,
    subscribed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_ws_subscriptions_session_sub ON websocket_subscriptions(session_id, subscription_id);
CREATE INDEX        idx_ws_subscriptions_topic ON websocket_subscriptions(topic_key);
