/**
 * User management — admin-driven CRUD on the {@code users} table and the
 * {@code user_application_assignments} join table (SPEC §3.1).
 *
 * <p>Covers SPEC §4.3 "Users" group: list/create/update/delete users, reset
 * password, and per-user application assignment. Self-service login and
 * change-password live in {@code com.bpl.orderapp.admin.auth}.
 */
package com.bpl.orderapp.admin.user;
