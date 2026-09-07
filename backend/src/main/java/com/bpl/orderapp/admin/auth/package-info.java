/**
 * Authentication endpoints and self-service account flows.
 *
 * <p>Covers SPEC §4.3 "Auth" group: login, logout, {@code /auth/me}, and the
 * forced change-password flow (SPEC §8.1). Persistent state lives on the
 * {@code users} table (SPEC §3.1); this package owns the session-cookie
 * issuance side of that table, not user CRUD (which is in
 * {@code com.bpl.orderapp.admin.user}).
 */
package com.bpl.orderapp.admin.auth;
