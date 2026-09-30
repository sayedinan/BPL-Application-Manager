package com.bpl.orderapp.admin.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code PUT /api/v1/users/{id}}.
 *
 * <p>All fields are optional at the JSON level, but at least one
 * must be present (the endpoint updates role, assignments, email,
 * and phone number together — sending none is a no-op and should be
 * rejected). If the caller passes an empty list for
 * assignedApplicationIds, the user has all applications unassigned
 * (a deliberate reset, per SPEC §3.1's assignment model).
 *
 * <p>Unlike the SSH password field, email does NOT support
 * "omit means keep existing" semantics for clearing — every user
 * must have a valid email (per notification-module requirements),
 * so if email is present in the request body it must be non-blank
 * and well-formed. Omitting it entirely from the request still
 * means "leave the current value alone."
 *
 * <p>phoneNumber, unlike email, is genuinely optional (nullable
 * column, no SMS alerts is a valid state) — an empty string clears
 * it, a value present must match V15's CHECK constraint shape, and
 * omitting the field entirely leaves the current value alone, same
 * "omit means keep existing" semantics as the SSH password field.
 *
 * <p>The caller must have the Admin+ role (per SPEC §4.3) to
 * reach this endpoint; the controller does not enforce the role
 * gate itself (the security filter chain does, when it lands).
 */
public record UpdateUserRequest(
    String username,
    @Size(max = 150) String fullName,
    String role,
    List<Long> assignedApplicationIds,
    @Email String email,
    @Pattern(regexp = "^8801[0-9]{9}$|^$", message = "phoneNumber must be 8801 followed by 9 digits, or empty to clear it")
    String phoneNumber
) {
    public boolean hasChange() {
        return username != null
            || fullName != null
            || role != null
            || (assignedApplicationIds != null && !assignedApplicationIds.isEmpty())
            || email != null
            || phoneNumber != null;
    }
}
