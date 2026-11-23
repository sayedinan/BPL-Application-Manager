-- V11 — drop the stored status column; add status_script.
--
-- Source of truth: STATUS-REDESIGN.md.
--
-- Per the status redesign: online/offline is now a *derived* value,
-- computed live by the status poller, never written by start/stop
-- endpoints. The old status/started_at columns and the RUNNING/
-- STOPPED/STARTING/STOPPING/ERROR CHECK constraint (V2) are removed
-- entirely — there is no persisted status enum anymore.
--
-- `status_script` is the 4th Sys.Admin-authored script (alongside
-- start_script/stop_script/log_script from V2), run over SSH on its
-- own cached connection and its own hardcoded poll interval.
--
-- DEFAULT '' exists only so this ALTER succeeds against existing rows
-- that predate this column; it is dropped immediately after so any
-- future INSERT must supply a real value, matching the other three
-- script columns' NOT NULL-with-no-default shape.

ALTER TABLE applications DROP CONSTRAINT applications_status_check;
ALTER TABLE applications DROP COLUMN status;
ALTER TABLE applications DROP COLUMN started_at;

ALTER TABLE applications ADD COLUMN status_script TEXT NOT NULL DEFAULT '';
ALTER TABLE applications ALTER COLUMN status_script DROP DEFAULT;
