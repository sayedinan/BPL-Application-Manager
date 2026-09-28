package com.bpl.orderapp.admin.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;

import java.time.Instant;
import java.util.Set;

/**
 * Thin wrapper around {@code JavaMailSender}. Only instantiated by
 * {@code NotificationConfig} when {@code spring.mail.username} is
 * actually set — see that class and this package's package-info for
 * why the mailer is optional rather than required at startup.
 *
 * <p>{@code @Async} so a slow/unresponsive SMTP server never blocks
 * the caller. Requires {@code @EnableAsync} on the main application
 * class.
 *
 * <p>Deliberately kept as one generic {@link #notifyLifecycleEvent}
 * method covering all four application-lifecycle email types, rather
 * than four near-duplicate methods — also keeps the door open for
 * this same mailer to send OTP codes later without restructuring.
 */
public class EmailNotificationService implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotificationService(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * @param actorUsername null for EXTERNAL_ONLINE/EXTERNAL_OFFLINE (no logged-in actor exists)
     * @param actorRole null for the same two cases
     * @param flapNote optional extra sentence appended to the body (used when a
     *                 flapping application has just stabilized); null for the normal case
     */
    @Async
    @Override
    public void notifyLifecycleEvent(Set<String> recipients, String appName, LifecycleEventType eventType,
            String actorUsername, String actorRole, String flapNote) {
        if (recipients.isEmpty()) {
            return;
        }
        String timestamp = Instant.now().toString();
        String subject;
        String body;
        switch (eventType) {
            case STARTED_VIA_DASHBOARD -> {
                subject = "BPL: " + appName + " started";
                body = appName + " was started via the BPL dashboard by " + actorUsername
                    + " (" + actorRole + ") at " + timestamp + ".";
            }
            case STOPPED_VIA_DASHBOARD -> {
                subject = "BPL: " + appName + " stopped";
                body = appName + " was stopped via the BPL dashboard by " + actorUsername
                    + " (" + actorRole + ") at " + timestamp + ".";
            }
            case EXTERNAL_ONLINE -> {
                subject = "⚠️ BPL ALERT: " + appName + " came online outside the dashboard";
                body = appName + " came online WITHOUT being started from the BPL dashboard. "
                    + "This may mean the application recovered from a crash on its own, or "
                    + "someone started it directly on the server. Detected at " + timestamp
                    + ". Please verify.";
            }
            case EXTERNAL_OFFLINE -> {
                subject = "⚠️ BPL ALERT: " + appName + " went offline outside the dashboard";
                body = appName + " went offline WITHOUT being stopped from the BPL dashboard. "
                    + "This may mean the application crashed, or someone stopped it directly "
                    + "on the server. Detected at " + timestamp + ". Please investigate.";
            }
            default -> throw new IllegalArgumentException("Unknown lifecycle event type: " + eventType);
        }
        if (flapNote != null) {
            body = body + " " + flapNote;
        }
        sendToAll(recipients, subject, body);
    }

    // Plain (non-@Async) helper — notifyLifecycleEvent above is the
    // actual async entry point; a method can't usefully be @Async
    // when called from another method on the same instance (Spring's
    // proxy is bypassed on self-invocation), so the looping/sending
    // logic lives here instead, one level down.
    private void sendToAll(Set<String> recipients, String subject, String body) {
        for (String recipient : recipients) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromAddress);
                message.setTo(recipient);
                message.setSubject(subject);
                message.setText(body);
                mailSender.send(message);
            } catch (Exception e) {
                log.warn("Failed to send notification email to {}: {}", recipient, e.getMessage());
            }
        }
    }
}
