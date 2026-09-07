package com.bpl.orderapp.admin.common;

/**
 * Thrown when a credential check fails — either because no such user
 * exists, the user is soft-deleted, or the password doesn't match.
 *
 * <p>Maps to HTTP 401 with the {@code INVALID_CREDENTIALS} error code
 * via {@link GlobalExceptionHandler}. The two failure modes
 * ("no such user" and "wrong password") are intentionally
 * indistinguishable in the response — see {@code AuthController} for
 * the rationale.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
