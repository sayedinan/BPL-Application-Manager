package com.bpl.orderapp.admin.common;

/**
 * Thrown when a referenced entity does not exist (or is soft-deleted
 * — see the per-endpoint filter).
 *
 * <p>Maps to HTTP 404 with a generic message via
 * {@link GlobalExceptionHandler}. The detail does not echo the
 * requested id back to the client, so this exception is safe to
 * throw on both "never existed" and "soft-deleted" paths.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("Resource not found");
    }
}
