package com.bpl.orderapp.admin.common;

/**
 * Locked error code constants from SPEC §4.2.
 *
 * <p>This is the application-side mirror of the SPEC's error code
 * table. Adding a new code requires updating both the SPEC and
 * this class. Server-raised codes (currently only
 * {@code INVALID_CREDENTIALS} and {@code VALIDATION_FAILED}) are
 * mapped to HTTP responses in {@link GlobalExceptionHandler};
 * client-raised codes (e.g. {@code DUPLICATE_NAME} on a 409
 * from a unique-constraint violation) will be mapped when the
 * corresponding endpoints exist.
 */
public final class ErrorCode {

    private ErrorCode() {
        // utility class
    }

    public static final String INVALID_CREDENTIALS         = "INVALID_CREDENTIALS";
    public static final String ACCESS_DENIED               = "ACCESS_DENIED";
    public static final String VALIDATION_FAILED           = "VALIDATION_FAILED";
    public static final String SHELLCHECK_FAILED           = "SHELLCHECK_FAILED";
    public static final String APPLICATION_ALREADY_RUNNING = "APPLICATION_ALREADY_RUNNING";
    public static final String APPLICATION_ALREADY_STOPPED = "APPLICATION_ALREADY_STOPPED";
    public static final String SSH_CONNECTION_FAILED       = "SSH_CONNECTION_FAILED";
    public static final String SSH_AUTH_FAILED             = "SSH_AUTH_FAILED";
    public static final String SSH_COMMAND_FAILED          = "SSH_COMMAND_FAILED";
    public static final String SELF_DELETE_FORBIDDEN       = "SELF_DELETE_FORBIDDEN";
    public static final String DUPLICATE_NAME              = "DUPLICATE_NAME";
    public static final String IDEMPOTENCY_CONFLICT        = "IDEMPOTENCY_CONFLICT";
    // STATUS-REDESIGN.md §3: the start/stop SSH command itself succeeded,
    // but the status poller never confirmed the expected online/offline
    // flip within the timeout window. Distinct from SSH_COMMAND_FAILED
    // (which means the remote command errored) and SSH_CONNECTION_FAILED/
    // SSH_AUTH_FAILED (which mean we couldn't even reach/authenticate).
    public static final String STATUS_CONFIRMATION_TIMEOUT = "STATUS_CONFIRMATION_TIMEOUT";
}
