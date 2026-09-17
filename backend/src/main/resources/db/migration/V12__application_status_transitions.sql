-- V12 — application_status_transitions + application_status_streak.
--
-- Source of truth: STATUS-REDESIGN.md §4.
--
-- Two tables, two different jobs:
--
--   * application_status_transitions — insert-only log, one row per
--     detected online/offline flip. This is NOT audit — it never
--     records who caused the change (that's audit_logs' job via
--     START_APPLICATION/STOP_APPLICATION). This table only answers
--     "when did this app's reachable state change," which is what
--     total-uptime/downtime-since-deploy is computed from.
--
--   * application_status_streak — one row per application, updated
--     (not appended) on every flip, so "how long has it been in the
--     current state" is a cheap single-row lookup instead of scanning
--     application_status_transitions and finding the most recent flip
--     every time the dashboard asks.
--
-- Both cascade-delete with the application (same pattern as
-- application_log_lines in V5) — this is stats about a specific app,
-- not a permanent record like audit_logs, so there's no reason to
-- keep it around after the app itself is gone.

CREATE TABLE application_status_transitions (
    id               BIGSERIAL    PRIMARY KEY,
    application_id   BIGINT       NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    online           BOOLEAN      NOT NULL,
    transitioned_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_status_transitions_app ON application_status_transitions(application_id, transitioned_at DESC);

CREATE TABLE application_status_streak (
    application_id     BIGINT       PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    online             BOOLEAN      NOT NULL,
    streak_started_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
