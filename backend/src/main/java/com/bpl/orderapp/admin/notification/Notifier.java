package com.bpl.orderapp.admin.notification;


/**
 * A channel that can deliver an application-lifecycle notification.
 *
 * <p>Implemented by {@link EmailNotificationService} and (once added)
 * {@code SmsNotificationService}. Callers such as {@code
 * StatusPollingOrchestrator} and {@code ApplicationController} hold a
 * {@code List<Notifier>} (Spring auto-injects every bean implementing
 * this interface) and loop over it, rather than calling each channel
 * by name — adding a future channel means writing one new class that
 * implements this interface, with zero changes to the call sites.
 *
 * <p>{@code recipients} is a generic string identifier per channel —
 * an email address for {@code EmailNotificationService}, a phone
 * number for {@code SmsNotificationService} — resolved beforehand by
 * {@link NotificationRecipientResolver}.
 */
public interface Notifier {

    /**
     * @param actorUsername null for EXTERNAL_ONLINE/EXTERNAL_OFFLINE (no logged-in actor exists)
     * @param actorRole null for the same two cases
     * @param flapNote optional extra sentence appended to the body (used when a
     *                 flapping application has just stabilized); null for the normal case
     */
    void notifyLifecycleEvent(Long applicationId, String appName, LifecycleEventType eventType,
            String actorUsername, String actorRole, String flapNote);
}
