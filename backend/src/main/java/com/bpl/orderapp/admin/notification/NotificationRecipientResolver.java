package com.bpl.orderapp.admin.notification;

import com.bpl.orderapp.admin.user.UserRepository;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Works out who should receive an offline-alert notification (email
 * or SMS) for a given application.
 *
 * <p>Recipients are the union of:
 * <ul>
 *   <li>every SYS_ADMIN/ADMIN user with a non-null email — they see
 *       alerts for all applications, not just ones assigned to them
 *       (assignments only gate USER-role visibility elsewhere in the
 *       app, e.g. the dashboard and logs)</li>
 *   <li>every USER-role user with a non-null email who is assigned
 *       to this specific application via
 *       {@code user_application_assignments}</li>
 * </ul>
 *
 * <p>A {@code LinkedHashSet} de-duplicates in case a user's email
 * would otherwise be pulled in by both branches (not possible today
 * since a user has exactly one role, but harmless to guard against
 * if that ever changes) and keeps a stable, predictable order.
 */
public class NotificationRecipientResolver {

    private final UserRepository userRepository;

    public NotificationRecipientResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Set<String> resolveForApplication(Long applicationId) {
        Set<String> recipients = new LinkedHashSet<>();
        recipients.addAll(userRepository.findAdminEmails());
        recipients.addAll(userRepository.findAssignedUserEmails(applicationId));
        return recipients;
    }

    /**
     * Same recipient rule as {@link #resolveForApplication}, but
     * returns phone numbers (for {@code SmsNotificationService})
     * instead of email addresses. Kept as a separate method so the
     * two channels can diverge later without unwinding shared code.
     */
    public Set<String> resolvePhoneNumbersForApplication(Long applicationId) {
        Set<String> recipients = new LinkedHashSet<>();
        recipients.addAll(userRepository.findAdminPhoneNumbers());
        recipients.addAll(userRepository.findAssignedUserPhoneNumbers(applicationId));
        return recipients;
    }

    /**
     * SYS_ADMIN/ADMIN emails only, with no application scoping — used
     * by {@link EmailNotificationService#sendAdminAlert} for
     * operational alerts that aren't tied to a specific application.
     */
    public Set<String> resolveAdminEmails() {
        return new LinkedHashSet<>(userRepository.findAdminEmails());
    }
}
