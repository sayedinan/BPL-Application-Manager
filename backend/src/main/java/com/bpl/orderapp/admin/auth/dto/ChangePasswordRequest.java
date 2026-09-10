package com.bpl.orderapp.admin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/change-password}.
 *
 * <p>Carries the user's identity (username), the current password
 * (for re-validation — see {@code AuthController} for why this is
 * required even though login already validated it), and the new
 * password.
 *
 * <p>The current password is the only piece of "old" data the
 * requester knows; without re-validating it, anyone who can put a
 * username in a request body can reset that user's password. When
 * sessions are added, the {@code username} field will be replaced
 * with the session-derived principal; the {@code oldPassword} field
 * becomes optional in that case (the session is itself proof of
 * identity).
 */
public record ChangePasswordRequest(

    @NotBlank(message = "username must not be blank")
    @Size(max = 64, message = "username must be at most 64 characters")
    String username,

    @NotBlank(message = "oldPassword must not be blank")
    @Size(max = 256, message = "oldPassword must be at most 256 characters")
    String oldPassword,

    @NotBlank(message = "newPassword must not be blank")
    @Size(min = 12, max = 256, message = "newPassword must be at least 12 and at most 256 characters")
    String newPassword
) {
}
