/**
 * Email notification module — sends alerts when an application goes
 * unexpectedly offline, to SYS_ADMIN/ADMIN users (all applications)
 * and USER-role users (only applications assigned to them via
 * {@code user_application_assignments}).
 *
 * <p>Built on Spring's {@code JavaMailSender} over Gmail SMTP. Mail
 * credentials are optional (unlike {@code DB_PASSWORD}/
 * {@code SSH_CIPHER_KEY}): if {@code spring.mail.username} is unset,
 * {@code NotificationConfig} does not create the beans in this
 * package at all, and callers (e.g. {@code StatusPollingOrchestrator})
 * hold an {@code Optional<EmailNotificationService>} and simply skip
 * sending when it's empty.
 *
 * <p>Deliberately named generically ({@code EmailNotificationService},
 * not e.g. {@code OfflineAlertService}) because this mailer is planned
 * to be reused for OTP delivery later — that is not implemented yet.
 */
package com.bpl.orderapp.admin.notification;
