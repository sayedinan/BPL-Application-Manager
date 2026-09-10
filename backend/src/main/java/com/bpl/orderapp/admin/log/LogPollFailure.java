package com.bpl.orderapp.admin.log;
public class LogPollFailure {
    // §7.4: on connection/auth failure, write '[ERROR] Log poll failed: <reason>' to app log stream,
    // invalidate cached SSH connection (lazy reconnect next tick), no silent retries.
    public static String message(String reason) { return "[ERROR] Log poll failed: " + reason; }
}
