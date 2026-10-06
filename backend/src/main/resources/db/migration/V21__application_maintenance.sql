-- V21 - maintenance windows (HEALTH-MONITORING.md, alert silencing).
--
-- A window is set from the dashboard for ONE application, for a limited time.
-- While it is open the application's online/offline transitions are still
-- recorded and audited, but the alert emails/SMS for them are held back. When
-- the window ends (it runs out, or someone ends it early) and the application
-- is still offline, the held-back alert is sent then, because no further
-- transition will ever trigger it.
--
-- At most one window per application: starting a new one replaces the old.
-- It disappears with its application (same cascade pattern as V5, V12, V20).
-- The audit log keeps the history of who started and ended windows, so this
-- table only holds what is open right now.

CREATE TABLE application_maintenance (
    application_id  BIGINT        PRIMARY KEY
                                    REFERENCES applications(id) ON DELETE CASCADE,
    ends_at         TIMESTAMPTZ   NOT NULL,
    note            VARCHAR(200),
    started_by      TEXT          NOT NULL,
    started_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
