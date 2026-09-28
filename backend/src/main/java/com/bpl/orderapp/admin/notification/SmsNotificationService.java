package com.bpl.orderapp.admin.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Sends application-lifecycle SMS alerts via the Durbar SMS gateway.
 *
 * <p>Mirrors {@link EmailNotificationService}'s shape closely — same
 * four lifecycle events, same recipient-scope rules (resolved
 * separately via {@link NotificationRecipientResolver#resolvePhoneNumbersForApplication}),
 * same {@code @Async} dispatch so a slow Durbar response never blocks
 * {@code StatusPollingOrchestrator}.
 *
 * <p>Sends one Durbar API call per recipient (not one batched call
 * with comma-separated numbers, even though Durbar's API supports
 * that) — per-recipient calls let a single bad number's failure be
 * attributed and logged precisely, and let the escalation email name
 * exactly which number failed. See {@code sms_send_log} (V16).
 *
 * <p>On any send failure ({@code isError: true}, or the HTTP call
 * itself throwing), this fails silently for the SMS recipient
 * (matching {@code EmailNotificationService}'s no-retry policy) but
 * additionally sends an escalation email to SYS_ADMIN/ADMIN via
 * {@link EmailNotificationService#sendAdminAlert}, since SMS failures
 * are otherwise invisible.
 *
 * <p>Only instantiated by {@code NotificationConfig} when {@code
 * durbar.sms.user-id} is set — see that class.
 */
public class SmsNotificationService implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationService.class);

    private final DurbarSmsClient durbarSmsClient;
    private final JdbcTemplate jdbc;
    private final NotificationRecipientResolver recipientResolver;
    private final Optional<EmailNotificationService> emailNotificationService;

    public SmsNotificationService(DurbarSmsClient durbarSmsClient, JdbcTemplate jdbc,
            NotificationRecipientResolver recipientResolver,
            Optional<EmailNotificationService> emailNotificationService) {
        this.durbarSmsClient = durbarSmsClient;
        this.jdbc = jdbc;
        this.recipientResolver = recipientResolver;
        this.emailNotificationService = emailNotificationService;
    }

    @Async
    @Override
    public void notifyLifecycleEvent(Long applicationId, String appName, LifecycleEventType eventType,
            String actorUsername, String actorRole, String flapNote) {
        Set<String> recipients = recipientResolver.resolvePhoneNumbersForApplication(applicationId);
        if (recipients.isEmpty()) {
            return;
        }
        String smsText = buildMessage(appName, eventType, actorUsername, actorRole, flapNote);
        for (String phoneNumber : recipients) {
            sendOne(applicationId, phoneNumber, eventType, smsText);
        }
    }

    // applicationId is now threaded through to logAttempt so
    // sms_send_log records which application triggered the SMS.
    private void sendOne(Long applicationId, String phoneNumber, LifecycleEventType eventType, String smsText) {
        DurbarSmsClient.SmsSendResult result;
        try {
            result = durbarSmsClient.send(phoneNumber, smsText);
        } catch (Exception e) {
            result = new DurbarSmsClient.SmsSendResult(true, null, "Unexpected error: " + e.getMessage());
        }

        logAttempt(applicationId, phoneNumber, eventType, result);

        if (result.isError()) {
            log.warn("SMS send failed for {}: {}", phoneNumber, result.message());
            escalate(phoneNumber, eventType, result);
        }
    }

    private void logAttempt(Long applicationId, String phoneNumber, LifecycleEventType eventType,
            DurbarSmsClient.SmsSendResult result) {
        try {
            jdbc.update(
                "INSERT INTO sms_send_log (application_id, recipient_phone_number, event_type, "
                    + "is_error, inserted_sms_id, response_message, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                applicationId, phoneNumber, eventType.name(), result.isError(),
                result.insertedSmsIds(), result.message(), java.sql.Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.warn("Failed to write sms_send_log row for {}: {}", phoneNumber, e.getMessage());
        }
    }

    private void escalate(String phoneNumber, LifecycleEventType eventType, DurbarSmsClient.SmsSendResult result) {
        if (emailNotificationService.isEmpty()) {
            return; // no email module active either — nothing left to escalate through
        }
        String subject = "⚠️ BPL ALERT: SMS delivery failed";
        String body = "An SMS notification (" + eventType + ") to " + phoneNumber
            + " failed to send via Durbar. Reason: " + result.message()
            + ". Detected at " + Instant.now() + ".";
        emailNotificationService.get().sendAdminAlert(subject, body);
    }

    private String buildMessage(String appName, LifecycleEventType eventType, String actorUsername,
            String actorRole, String flapNote) {
        String message;
        switch (eventType) {
            case STARTED_VIA_DASHBOARD ->
                message = "BPL: " + appName + " started via dashboard by " + actorUsername + " (" + actorRole + ").";
            case STOPPED_VIA_DASHBOARD ->
                message = "BPL: " + appName + " stopped via dashboard by " + actorUsername + " (" + actorRole + ").";
            case EXTERNAL_ONLINE ->
                message = "BPL ALERT: " + appName + " came online OUTSIDE the dashboard. Please verify.";
            case EXTERNAL_OFFLINE ->
                message = "BPL ALERT: " + appName + " went offline OUTSIDE the dashboard. Please investigate.";
            default -> throw new IllegalArgumentException("Unknown lifecycle event type: " + eventType);
        }
        if (flapNote != null) {
            message = message + " (Was flapping, now stabilized.)";
        }
        return message;
    }
}
