package com.bpl.orderapp.admin.auth.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/v1/auth/me} (SPEC §4.3).
 *
 * <p>Carries everything the SPA needs to render the user-aware
 * portions of the UI in a single round-trip: the user identity
 * (id, username, role), the {@code mustChangePassword} flag that
 * drives the change-password redirect, and the list of
 * application IDs the user is assigned to (used to filter the
 * dashboard's application grid per SPEC §2's row about USER's
 * "assigned applications only" visibility).
 *
 * <p>The shape is a strict superset of {@link LoginResponse}; the
 * only new field is {@code assignedApplicationIds}. Both DTOs are
 * the wire shape only — the {@code MeController} reads from the
 * database on each call, so a user's assignments are always
 * current (no stale state from the moment they logged in).
 */
public record MeResponse(
    Long id,
    String username,
    String role,
    boolean mustChangePassword,
    List<Long> assignedApplicationIds
) {
}
