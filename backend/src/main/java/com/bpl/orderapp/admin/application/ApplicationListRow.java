package com.bpl.orderapp.admin.application;

import java.time.Instant;

/** One row of the dashboard list: an application plus its current status streak, if any. */
public record ApplicationListRow(
        Long id,
        String name,
        String groupName,
        Boolean online,          // null when the app has no streak row yet
        Instant streakStartedAt  // null when the app has no streak row yet
) {
}
