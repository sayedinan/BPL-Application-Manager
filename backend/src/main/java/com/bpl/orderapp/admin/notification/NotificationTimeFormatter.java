package com.bpl.orderapp.admin.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats timestamps for notification emails in Bangladesh local
 * time, e.g. "28 Sep 2026, 5:28 PM (GMT+6)".
 *
 * <p>Bangladesh has no DST, so the offset is a fixed literal rather
 * than derived from the ZoneId's own zone-name formatting.
 */
final class NotificationTimeFormatter {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a");

    private NotificationTimeFormatter() {}

    static String format(Instant instant) {
        return FORMAT.format(instant.atZone(DHAKA)) + " (GMT+6)";
    }
}
