-- V10 — add UPDATE_USER to the audit_logs action_type enum.
--
-- Editing an existing user (role change and/or reassigning
-- applications, via PUT /api/v1/users/{id}) had no representable
-- action type at all — UPDATE_APPLICATION existed for apps, but
-- there was no user-side equivalent, so this category of change was
-- structurally impossible to audit, not just unwired.
--
-- Postgres auto-names an inline column CHECK constraint as
-- <table>_<column>_check when none is given explicitly, which is
-- what V4 relied on — so audit_logs_action_type_check is the actual
-- constraint name being dropped and recreated here.
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_type_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_type_check
    CHECK (action_type IN (
        'START_APPLICATION',
        'STOP_APPLICATION',
        'CREATE_APPLICATION',
        'UPDATE_APPLICATION',
        'DELETE_APPLICATION',
        'ASSIGN_APPLICATION',
        'CREATE_USER',
        'CREATE_ADMIN',
        'UPDATE_USER',
        'DELETE_USER',
        'RESET_PASSWORD',
        'CHANGE_PASSWORD',
        'LOGIN',
        'LOGOUT',
        'SHELLCHECK_UNAVAILABLE'
    ));
