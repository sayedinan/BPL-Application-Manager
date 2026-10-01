package com.bpl.orderapp.admin.health;

import java.util.Locale;

/**
 * Overall or per-check status, as defined in HEALTH_CONTRACT.md.
 *
 * <p>How each value maps to online/offline is decided in
 * HEALTH-MONITORING.md §3, not here: UP and DEGRADED are online, DOWN
 * is offline, UNKNOWN means "leave the previous state alone".
 */
public enum HealthStatus {
    UP,
    DEGRADED,
    DOWN,
    UNKNOWN;

    /**
     * True when {@code raw} is exactly one of the four contract values
     * (case-insensitive). Used by the contract parser to reject a
     * response whose overall status is missing or made up, instead of
     * quietly treating it as UNKNOWN.
     */
    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Tolerant parse for optional per-check statuses: null, blank or
     * unrecognized text becomes UNKNOWN rather than an error.
     */
    public static HealthStatus fromString(String raw) {
        return isValid(raw) ? valueOf(raw.trim().toUpperCase(Locale.ROOT)) : UNKNOWN;
    }
}
