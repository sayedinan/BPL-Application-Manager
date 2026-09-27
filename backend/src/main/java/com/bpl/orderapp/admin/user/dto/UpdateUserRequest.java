package com.bpl.orderapp.admin.user.dto;

import jakarta.validation.constraints.Email;
import java.util.List;

/**
 * Request body for {@code PUT /api/v1/users/{id}}.
 *
 * <p>All fields are optional at the JSON level, but at least one
 * must be present (the endpoint updates role, assignments, and
 * email together — sending none is a no-op and should be rejected).
 * If the caller passes an empty list for assignedApplicationIds,
 * the user has all applications unassigned (a deliberate reset,
 * per SPEC §3.1's assignment model).
 *
 * <p>Unlike the SSH password field, email does NOT support
 * "omit means keep existing" semantics for clearing — every user
 * must have a valid email (per notification-module requirements),
 * so if email is present in the request body it must be non-blank
 * and well-formed. Omitting it entirely from the request still
 * means "leave the current value alone."
 *
 * <p>The caller must have the Admin+ role (per SPEC §4.3) to
 * reach this endpoint; the controller does not enforce the role
 * gate itself (the security filter chain does, when it lands).
 */
public record UpdateUserRequest(
    String username,
    String role,
    List<Long> assignedApplicationIds,
    @Email String email
) {
    public boolean hasChange() {
        return username != null
            || role != null
            || (assignedApplicationIds != null && !assignedApplicationIds.isEmpty())
            || email != null;
    }
}
