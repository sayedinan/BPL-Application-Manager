package com.bpl.orderapp.admin.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;

import java.util.Set;

/**
 * Thin wrapper around {@code JavaMailSender}. Only instantiated by
 * {@code NotificationConfig} when {@code spring.mail.username} is
 * actually set — see that class and this package's package-info for
 * why the mailer is optional rather than required at startup.
 *
 * <p>{@code @Async} so a slow/unresponsive SMTP server never blocks
 * the caller (e.g. the status-polling thread that detected the
 * application went offline). Requires {@code @EnableAsync} on the
 * main application class, and Spring's default {@code SimpleAsyncTaskExecutor}
 * is fine here given the low, bursty volume of offline alerts.
 *
 * <p>Failures are logged, not thrown — a bad SMTP config or a
 * temporarily-down mail server must never take down the caller's
 * actual work (recording the transition, broadcasting over
 * WebSocket). Notification delivery is best-effort.
 */
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotificationService(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Async
    public void sendToAll(Set<String> recipients, String subject, String body) {
        if (recipients.isEmpty()) {
            return;
        }
        for (String recipient : recipients) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromAddress);
                message.setTo(recipient);
                message.setSubject(subject);
                message.setText(body);
                mailSender.send(message);
            } catch (Exception e) {
                // One bad address must not stop the rest of the list
                // from being notified.
                log.warn("Failed to send notification email to {}: {}", recipient, e.getMessage());
            }
        }
    }
}
