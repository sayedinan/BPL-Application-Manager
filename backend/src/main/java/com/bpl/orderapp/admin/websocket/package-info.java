/**
 * WebSocket transport — STOMP-over-native-WebSocket configuration for live
 * log streaming and audit-log push.
 *
 * <p>Endpoint {@code /ws}, session-cookie auth, topics
 * {@code /topic/application-logs/{id}} and {@code /topic/audit-log}, and
 * per-user / per-application / global connection limits are all defined in
 * SPEC §5. This package owns the broker/transport wiring only; payload
 * production lives in the {@code application}, {@code log}, and
 * {@code audit} packages.
 */
package com.bpl.orderapp.admin.websocket;
