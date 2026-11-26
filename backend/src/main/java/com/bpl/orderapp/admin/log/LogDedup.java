package com.bpl.orderapp.admin.log;

import java.util.List;

/** SPEC §7.2: which lines of a fresh snapshot have not been stored yet. */
public final class LogDedup {

    private LogDedup() {
    }

    /**
     * @param snapshot what the log script just returned, oldest first
     * @param stored   the script lines already saved, oldest first (no poller error lines)
     * @return the part of the snapshot that comes after what is stored
     */
    public static List<String> newLines(List<String> snapshot, List<String> stored) {
        if (stored.isEmpty()) {
            return snapshot;
        }
        // Find where the stored tail ends inside the snapshot, comparing the whole run of
        // lines and not just the last one, which may well repeat. The latest such place wins.
        for (int end = snapshot.size(); end > 0; end--) {
            int length = Math.min(end, stored.size());
            if (snapshot.subList(end - length, end)
                    .equals(stored.subList(stored.size() - length, stored.size()))) {
                return snapshot.subList(end, snapshot.size());
            }
        }
        // Earlier lines differ (a filtering script, say): fall back to the last line alone.
        int last = snapshot.lastIndexOf(stored.get(stored.size() - 1));
        // Not found at all, as after rotation: take everything.
        return last < 0 ? snapshot : snapshot.subList(last + 1, snapshot.size());
    }
}
