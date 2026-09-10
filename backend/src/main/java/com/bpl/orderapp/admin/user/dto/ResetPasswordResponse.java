package com.bpl.orderapp.admin.user.dto;

/**
 * Response body for {@code POST /api/v1/users/{id}/reset-password}
 * (SPEC §12.2).
 *
 * <p>Carries the temporary password that the admin must deliver to
 * the user out-of-band (Slack / email / verbal). The value is
 * returned exactly once — the backend does not store it in
 * cleartext anywhere, and the audit row that records the reset
 * does NOT include the temp password (the
 * {@code audit-log-coverage} skill's "no secrets in any logged
 * field" rule).
 *
 * <p>The shape is intentionally minimal: a single field. Future
 * fields (e.g. an expiry timestamp on the temp password) can be
 * added without breaking the SPEC's contract, which only requires
 * {@code temporaryPassword}.
 */
public record ResetPasswordResponse(
    String temporaryPassword
) {
}
