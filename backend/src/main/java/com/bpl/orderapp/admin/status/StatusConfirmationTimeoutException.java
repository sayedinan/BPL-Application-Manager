package com.bpl.orderapp.admin.status;

/**
 * Thrown when a start/stop script exits successfully but the status
 * poller never confirms the expected online/offline flip within the
 * timeout window (STATUS-REDESIGN.md §3 — 30s after start, 15s after
 * stop). Maps to HTTP 504 with {@code STATUS_CONFIRMATION_TIMEOUT}
 * via {@link com.bpl.orderapp.admin.common.GlobalExceptionHandler}.
 *
 * <p>This is not the same failure as a non-zero script exit
 * ({@code SSH_COMMAND_FAILED}) — the SSH command itself succeeded
 * here. Reality (as observed by the status poller) just didn't catch
 * up within the window. The background status poller keeps running
 * after this is thrown and will still record the real state whenever
 * it actually settles.
 */
public class StatusConfirmationTimeoutException extends RuntimeException {

    public StatusConfirmationTimeoutException(Long applicationId, boolean expectedOnline) {
        super("Status confirmation timed out for application id=" + applicationId
            + " (expected online=" + expectedOnline + ")");
    }
}
