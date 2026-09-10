package com.bpl.orderapp.admin.common;

/**
 * The three RBAC tiers, per SPEC §1 / §2.
 *
 * <p>The string values are the same as the {@code role} column
 * in the {@code users} table (also used as the CHECK-constraint
 * values per SPEC §3.1). Persisted to / read from the DB as
 * these exact strings; don't rename a value without a migration.
 */
public enum Role {
    SYS_ADMIN,
    ADMIN,
    USER
}
