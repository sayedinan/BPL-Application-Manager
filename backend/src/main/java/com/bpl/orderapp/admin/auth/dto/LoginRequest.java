package com.bpl.orderapp.admin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/login}.
 *
 * <p>The cleartext password is the one piece of sensitive data that
 * crosses the wire in cleartext (TLS terminates at Caddy per SPEC
 * §8.3). It must never be logged, persisted, or echoed in error
 * responses — the credential check happens in the controller and
 * the value goes out of scope at request boundary.
 */
public record LoginRequest(

    @NotBlank(message = "username must not be blank")
    @Size(max = 64, message = "username must be at most 64 characters")
    String username,

    @NotBlank(message = "password must not be blank")
    @Size(max = 256, message = "password must be at most 256 characters")
    String password
) {
}
