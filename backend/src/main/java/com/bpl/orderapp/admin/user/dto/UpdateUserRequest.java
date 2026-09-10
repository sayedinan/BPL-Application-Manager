package com.bpl.orderapp.admin.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request body for {@code PUT /api/v1/users/{id}}.
 *
 * <p>Both fields are optional at the JSON level, but at least one
 * must be present (the endpoint updates both role and assignments
 * together — sending neither is a no-op and should be rejected).
 * If the caller passes an empty list for assignedApplicationIds,
 * the user has all applications unassigned (a deliberate reset,
 * per SPEC §3.1's assignment model).
 *
 * <p>The caller must have the Admin+ role (per SPEC §4.3) to
 * reach this endpoint; the controller does not enforce the role
 * gate itself (the security filter chain does, when it lands).
 */
public record UpdateUserRequest(
    String role,
    List<Long> assignedApplicationIds
) {
    public boolean hasChange() {
        return role != null || (assignedApplicationIds != null && !assignedApplicationIds.isEmpty());
    }
}
