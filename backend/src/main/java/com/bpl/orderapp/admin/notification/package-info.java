/**
 * Notification module — sends alerts when an application goes
 * unexpectedly offline (or comes online unexpectedly, or is
 * started/stopped via the dashboard), to SYS_ADMIN/ADMIN users (all
 * applications) and USER-role users (only applications assigned to
 * them via {@code user_application_assignments}).
 *
 * <p>Two independent channels implement the shared {@link Notifier}
 * interface:
 * <ul>
 *   <li>{@link EmailNotificationService} — Spring's {@code
 *       JavaMailSender} over Gmail SMTP. Active when {@code
 *       spring.mail.username} is set.</li>
 *   <li>{@link SmsNotificationService} — the company's internal
 *       Durbar SMS gateway, via {@link DurbarSmsClient}. Active when
 *       {@code durbar.sms.user-id} is set. Every send attempt is
 *       logged to {@code sms_send_log} (V16), and a send failure
 *       triggers an escalation email via {@link
 *       EmailNotificationService#sendAdminAlert} so SMS failures
 *       aren't silently invisible.</li>
 * </ul>
 *
 * <p>Callers ({@code StatusPollingOrchestrator}, {@code
 * ApplicationController}) hold a {@code List<Notifier>} — Spring
 * auto-injects every bean implementing the interface — and loop over
 * it, so a channel being unconfigured simply means a shorter list,
 * not a code branch. Adding a future channel means one new class
 * implementing {@link Notifier}, no changes to either caller.
 *
 * <p>Both mailers are deliberately named/structured generically
 * ({@code EmailNotificationService}, {@code SmsNotificationService} —
 * not e.g. {@code OfflineAlertService}) because both are planned to
 * be reused for OTP delivery later — that is not implemented yet.
 */
package com.bpl.orderapp.admin.notification;
