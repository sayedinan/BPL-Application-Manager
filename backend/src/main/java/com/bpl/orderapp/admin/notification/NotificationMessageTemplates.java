package com.bpl.orderapp.admin.notification;

/**
 * Placeholder substitution for per-application custom notification
 * templates (applications.email_*_message / sms_*_message, V17).
 *
 * <p>Supported placeholders: {@code {appName}}, {@code {actor}},
 * {@code {role}}, {@code {timestamp}}. {@code {actor}}/{@code {role}}
 * resolve to "N/A" for EXTERNAL_ONLINE/EXTERNAL_OFFLINE, since those
 * events have no logged-in actor.
 */
public final class NotificationMessageTemplates {

    private NotificationMessageTemplates() {
    }

    public static String substitute(String template, String appName, String actorUsername,
            String actorRole, String timestamp) {
        return template
            .replace("{appName}", appName == null ? "" : appName)
            .replace("{actor}", actorUsername == null ? "N/A" : actorUsername)
            .replace("{role}", actorRole == null ? "N/A" : actorRole)
            .replace("{timestamp}", timestamp == null ? "" : timestamp);
    }

    /**
     * Hard cap for SMS, applied AFTER substitution. The DB's CHECK
     * constraint only limits the raw template (before {appName} etc.
     * are expanded) — a 160-char template using {appName} can exceed
     * 160 once substituted, so this is the real guarantee sent to
     * Durbar never exceeds one SMS segment.
     */
    public static String truncateForSms(String text) {
        if (text.length() <= 160) {
            return text;
        }
        return text.substring(0, 157) + "...";
    }
}
