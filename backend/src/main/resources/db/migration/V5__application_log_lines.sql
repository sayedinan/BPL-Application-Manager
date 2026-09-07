-- V5 — application_log_lines table.
--
-- Source of truth: SPEC.md §3.1 (application_log_lines) and §7 (the
-- log poller that writes here).
--
-- Indexes map to the poller's two read paths:
--   * idx_application_log_unique on (application_id, line_number):
--     enforces the "monotonic per application" requirement the SPEC
--     calls out as a comment. The poller assigns line_number from an
--     in-memory counter, so a duplicate would indicate a poller bug
--     — this index catches that at INSERT time. Also serves the
--     "get line N for app X" lookup if the viewer ever needs it.
--   * idx_application_log_latest on (application_id, captured_at DESC):
--     serves the "last 500 lines" query that powers both the REST
--     initial load and the rolling trim. Ordering on captured_at
--     (not line_number) is intentional: the dedup algorithm in
--     SPEC §7.2 keys off the *content* of the last saved line, not
--     its line number, so the read path mirrors what the writer
--     reasons about.
--
-- `line_number` has no DB-side CHECK (e.g. > 0). The unique index
-- above is the only schema-level guarantee against duplicate line
-- numbers; the poller is responsible for the monotonic + positive
-- discipline. The rolling 500-line trim is also application-side.
--
-- ON DELETE CASCADE on application_id is correct: applications are
-- hard-deleted (SPEC §3.3), so deleting an application should remove
-- its log lines along with it. There is no scenario where a log
-- line outlives its application.

CREATE TABLE application_log_lines (
    id             BIGSERIAL    PRIMARY KEY,
    application_id BIGINT       NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    line_number    BIGINT       NOT NULL,                          -- Monotonic per application
    content        TEXT         NOT NULL,
    captured_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_application_log_unique ON application_log_lines(application_id, line_number);
CREATE INDEX        idx_application_log_latest ON application_log_lines(application_id, captured_at DESC);
