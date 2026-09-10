package com.bpl.orderapp.admin.auth.dto;

/**
 * Response body for {@code POST /api/v1/auth/login} on success.
 *
 * <p>Carries just enough information for the frontend to route the
 * user: id, username, role, and the {@code mustChangePassword} flag
 * that drives the change-password redirect (SPEC §8.1). The list
 * of assigned application IDs is intentionally NOT here — that
 * belongs on {@code GET /auth/me} per SPEC §4.3.
 *
 * <p>When the session layer is added, the response will also set
 * an HttpOnly session cookie; the body's shape does not change.
 *
 * @param id                   the user's primary key
 * @param username             the username (echoed back, never null)
 * @param role                 one of {@code SYS_ADMIN}, {@code ADMIN}, {@code USER}
 * @param mustChangePassword   {@code true} if the user must rotate their password
 *                             before reaching any other page (SPEC §8.1)
 */
public record LoginResponse(
    Long id,
    String username,
    String role,
    boolean mustChangePassword
) {
}
