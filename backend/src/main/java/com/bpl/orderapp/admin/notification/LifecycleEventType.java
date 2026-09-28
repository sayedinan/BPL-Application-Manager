package com.bpl.orderapp.admin.notification;

/**
 * The application-lifecycle events that trigger a notification.
 *
 * <p>Extracted out of {@code EmailNotificationService} (where it
 * originally lived as a nested enum) so both {@code
 * EmailNotificationService} and {@code SmsNotificationService} can
 * share the same set of event types via the {@link Notifier}
 * interface, without one notifier depending on the other's class.
 */
public enum LifecycleEventType {
    /** Started via the BPL dashboard by a logged-in actor. Routine, calm wording. */
    STARTED_VIA_DASHBOARD,
    /** Stopped via the BPL dashboard by a logged-in actor. Routine, calm wording. */
    STOPPED_VIA_DASHBOARD,
    /** Came online without dashboard involvement. Urgent wording — nothing
     *  outside the dashboard should ever be controlling these applications. */
    EXTERNAL_ONLINE,
    /** Went offline without dashboard involvement. Urgent wording — same reasoning. */
    EXTERNAL_OFFLINE
}
