/**
 * Log pipeline — per-application background poller that runs the configured
 * log script over SSH, deduplicates the returned snapshot window, trims to
 * the last 500 lines, and broadcasts new lines over WebSocket.
 *
 * <p>Read access to the rolling 500-line history (initial REST load) and
 * live broadcast share the {@code application_log_lines} table with the
 * application package (SPEC §3.1). Deduplication algorithm, scheduling,
 * and SSH failure handling are specified in SPEC §7.
 */
package com.bpl.orderapp.admin.log;
