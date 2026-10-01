package com.bpl.orderapp.admin.health;

/**
 * What {@link HealthCheckService#check} concluded about an application
 * on one tick, in the terms StatusPollingOrchestrator needs
 * (HEALTH-MONITORING.md §3).
 */
public enum HealthDecision {

    /** No enabled health endpoint for this application: use the SSH status_script instead. */
    NOT_CONFIGURED,

    /** The application is online. */
    ONLINE,

    /** The application is offline (enough consecutive failed checks). */
    OFFLINE,

    /**
     * Nothing to conclude this time, so keep the last known state: the
     * next check isn't due yet, this was a first isolated failure, the
     * application reported UNKNOWN, or a check is already running.
     */
    UNCHANGED
}
