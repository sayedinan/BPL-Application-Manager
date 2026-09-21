-- V13 - add APPLICATION_ONLINE / APPLICATION_OFFLINE to the audit_logs
-- action_type enum. Same drop-and-recreate pattern as V10.
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
        'SHELLCHECK_UNAVAILABLE',
        'APPLICATION_ONLINE',
        'APPLICATION_OFFLINE'
    ));
