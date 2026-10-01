package com.bpl.orderapp.admin.health;

/**
 * Thrown when an application's health response cannot be used: not
 * JSON, not a JSON object, or missing one of the required fields of
 * HEALTH_CONTRACT.md (or of the Actuator equivalent).
 *
 * <p>The message is short and safe to store in
 * {@code application_health_latest.error} and show to a SYS_ADMIN. The
 * poller treats this the same as an unreachable application: a failed
 * check that counts toward {@code OFFLINE_AFTER_FAILURES}
 * (HEALTH-MONITORING.md §3).
 */
public class InvalidHealthResponseException extends RuntimeException {

    public InvalidHealthResponseException(String message) {
        super(message);
    }
}
