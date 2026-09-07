/**
 * Application domain — the managed BPL/PCL applications and their lifecycle.
 *
 * <p>Owns the {@code applications}, {@code application_log_lines}, and
 * {@code idempotency_keys} tables (SPEC §3.1), the CRUD + start/stop/log
 * endpoints (SPEC §4.3 "Applications"), the STOPPED/STARTING/RUNNING/STOPPING/ERROR
 * state machine (SPEC §6), and the edit/delete lock rules (SPEC §6.4).
 */
package com.bpl.orderapp.admin.application;
