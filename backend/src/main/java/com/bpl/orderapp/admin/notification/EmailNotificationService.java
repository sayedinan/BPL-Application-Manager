package com.bpl.orderapp.admin.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
 *
 * <p>Sends HTML, not plain text — built with inline CSS only (no
 * {@code <style>} blocks or external stylesheets), since many mail
 * clients — Gmail included, when it clips or forwards a message —
 * strip head-level styles. Inline styles survive everywhere.
 */
public class EmailNotificationService implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    // Colors: red for "something happened outside the dashboard",
    // blue for "routine action someone deliberately took".
    private static final String COLOR_URGENT = "#d32f2f";
    private static final String COLOR_ROUTINE = "#1565c0";
    private static final String COLOR_TEXT = "#1a1a1a";
    private static final String COLOR_MUTED = "#666666";
    private static final String COLOR_BORDER = "#e0e0e0";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final NotificationRecipientResolver recipientResolver;

    public EmailNotificationService(JavaMailSender mailSender, String fromAddress,
            NotificationRecipientResolver recipientResolver) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.recipientResolver = recipientResolver;
    }

    /**
     * @param actorUsername null for EXTERNAL_ONLINE/EXTERNAL_OFFLINE (no logged-in actor exists)
     * @param actorRole null for the same two cases
     * @param flapNote optional extra sentence appended to the body (used when a
     *                 flapping application has just stabilized); null for the normal case
     */
    @Async
    @Override
    public void notifyLifecycleEvent(Long applicationId, String appName, LifecycleEventType eventType,
            String actorUsername, String actorRole, String flapNote) {
        Set<String> recipients = recipientResolver.resolveForApplication(applicationId);
        if (recipients.isEmpty()) {
            return;
        }
        String timestamp = NotificationTimeFormatter.format(Instant.now());
        String subject;
        String headline;
        String detailRows;
        boolean urgent;

        switch (eventType) {
            case STARTED_VIA_DASHBOARD -> {
                subject = "BPL: " + appName + " started";
                headline = appName + " was started";
                urgent = false;
                detailRows = row("Application", appName)
                    + row("Action", "Started via the BPL dashboard")
                    + row("By", actorUsername + " (" + actorRole + ")")
                    + row("Detected at", timestamp);
            }
            case STOPPED_VIA_DASHBOARD -> {
                subject = "BPL: " + appName + " stopped";
                headline = appName + " was stopped";
                urgent = false;
                detailRows = row("Application", appName)
                    + row("Action", "Stopped via the BPL dashboard")
                    + row("By", actorUsername + " (" + actorRole + ")")
                    + row("Detected at", timestamp);
            }
            case EXTERNAL_ONLINE -> {
                subject = "⚠️ BPL ALERT: " + appName + " came online outside the dashboard";
                headline = appName + " came online WITHOUT being started from the dashboard";
                urgent = true;
                detailRows = row("Application", appName)
                    + row("Possible cause", "Crash recovery, or someone started it directly on the server")
                    + row("Detected at", timestamp);
            }
            case EXTERNAL_OFFLINE -> {
                subject = "⚠️ BPL ALERT: " + appName + " went offline outside the dashboard";
                headline = appName + " went offline WITHOUT being stopped from the dashboard";
                urgent = true;
                detailRows = row("Application", appName)
                    + row("Possible cause", "Crash, or someone stopped it directly on the server")
                    + row("Detected at", timestamp);
            }
            default -> throw new IllegalArgumentException("Unknown lifecycle event type: " + eventType);
        }

        String callToAction = urgent ? "Please investigate." : null;
        String html = renderTemplate(urgent, headline, detailRows, flapNote, callToAction);
        sendToAll(recipients, subject, html);
    }

    /**
     * Sends an operational alert email to all SYS_ADMIN/ADMIN users,
     * not tied to any application's lifecycle event. Currently used
     * by {@code SmsNotificationService} to report Durbar send
     * failures, but deliberately generic (subject/body, no enum) so
     * it can be reused for other admin-facing alerts later.
     */
    @Async
    public void sendAdminAlert(String subject, String body) {
        Set<String> admins = recipientResolver.resolveAdminEmails();
        String html = renderTemplate(true, subject, row("Details", body), null, null);
        sendToAll(admins, subject, html);
    }

    private String row(String label, String value) {
        return "<tr>"
            + "<td style=\"padding:6px 12px 6px 0;color:" + COLOR_MUTED + ";white-space:nowrap;vertical-align:top;\">"
            + escape(label) + "</td>"
            + "<td style=\"padding:6px 0;color:" + COLOR_TEXT + ";\">" + escape(value) + "</td>"
            + "</tr>";
    }

    // Emails are the one place user- and system-derived strings
    // (usernames, application names) get concatenated straight into
    // markup — escape them so a username like "<b>x</b>" can't inject
    // HTML into the rendered email.
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String renderTemplate(boolean urgent, String headline, String detailRowsHtml,
            String flapNote, String callToAction) {
        String accent = urgent ? COLOR_URGENT : COLOR_ROUTINE;
        String badge = urgent ? "⚠️ ALERT" : "BPL NOTIFICATION";

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:520px;margin:0 auto;\">");
        html.append("<div style=\"background:").append(accent).append(";color:#ffffff;")
            .append("padding:12px 20px;border-radius:6px 6px 0 0;font-size:13px;")
            .append("font-weight:bold;letter-spacing:0.5px;\">").append(badge).append("</div>");
        html.append("<div style=\"border:1px solid ").append(COLOR_BORDER).append(";border-top:none;")
            .append("border-radius:0 0 6px 6px;padding:20px;\">");
        html.append("<p style=\"margin:0 0 16px 0;font-size:16px;color:").append(COLOR_TEXT)
            .append(";font-weight:bold;\">").append(escape(headline)).append("</p>");
        html.append("<table style=\"font-size:14px;border-collapse:collapse;width:100%;\">")
            .append(detailRowsHtml).append("</table>");
        if (flapNote != null) {
            html.append("<p style=\"margin:16px 0 0 0;padding:10px 12px;background:#fff8e1;")
                .append("border-left:3px solid #f9a825;font-size:13px;color:").append(COLOR_TEXT)
                .append(";\">").append(escape(flapNote)).append("</p>");
        }
        if (callToAction != null) {
            html.append("<p style=\"margin:16px 0 0 0;font-size:14px;color:").append(accent)
                .append(";font-weight:bold;\">").append(escape(callToAction)).append("</p>");
        }
        html.append("</div>");
        html.append("<p style=\"margin:12px 0 0 0;font-size:11px;color:").append(COLOR_MUTED)
            .append(";\">BPL Application Manager — automated notification, please do not reply.</p>");
        html.append("</div>");
        return html.toString();
    }

    // Plain (non-@Async) helper — notifyLifecycleEvent/sendAdminAlert
    // above are the actual async entry points; a method can't
    // usefully be @Async when called from another method on the same
    // instance (Spring's proxy is bypassed on self-invocation), so
    // the looping/sending logic lives here instead, one level down.
    private void sendToAll(Set<String> recipients, String subject, String html) {
        for (String recipient : recipients) {
            try {
                MimeMessage mime = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
                helper.setFrom(fromAddress);
                helper.setTo(recipient);
                helper.setSubject(subject);
                helper.setText(html, true); // true = isHtml
                mailSender.send(mime);
            } catch (MessagingException | RuntimeException e) {
                log.warn("Failed to send notification email to {}: {}", recipient, e.getMessage());
            }
        }
    }
}
