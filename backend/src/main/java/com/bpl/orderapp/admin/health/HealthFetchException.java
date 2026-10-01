package com.bpl.orderapp.admin.health;

/**
 * Thrown by {@link HealthHttpClient} when an application's health
 * endpoint could not be fetched at all: timeout, connection refused,
 * unknown host, TLS failure, or a response that is too large.
 *
 * <p>The message is short, never contains the API key, and is safe to
 * store in {@code application_health_latest.error}. Like
 * {@link InvalidHealthResponseException}, the poller counts it as a
 * failed check (HEALTH-MONITORING.md §3).
 */
public class HealthFetchException extends RuntimeException {

    public HealthFetchException(String message) {
        super(message);
    }
}
