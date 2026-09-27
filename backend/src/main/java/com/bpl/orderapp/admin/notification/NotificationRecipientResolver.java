package com.bpl.orderapp.admin.notification;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out who should receive an offline-alert email for a given
 * application.
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
 *
 * <p>Soft-deleted users ({@code deleted_at IS NOT NULL}) are always
 * excluded — same filter every other query in this codebase uses.
 */
public class NotificationRecipientResolver {

    private final JdbcTemplate jdbc;

    public NotificationRecipientResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> resolveForApplication(Long applicationId) {
        Set<String> recipients = new LinkedHashSet<>();

        List<String> adminEmails = jdbc.queryForList(
            "SELECT email FROM users WHERE role IN ('SYS_ADMIN','ADMIN') "
                + "AND email IS NOT NULL AND deleted_at IS NULL",
            String.class);
        recipients.addAll(adminEmails);

        List<String> assignedUserEmails = jdbc.queryForList(
            "SELECT u.email FROM users u "
                + "JOIN user_application_assignments a ON a.user_id = u.id "
                + "WHERE a.application_id = ? AND u.role = 'USER' "
                + "AND u.email IS NOT NULL AND u.deleted_at IS NULL",
            String.class, applicationId);
        recipients.addAll(assignedUserEmails);

        return recipients;
    }
}
