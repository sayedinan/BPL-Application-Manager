package com.bpl.orderapp.admin.security;

/**
 * Discrete actions the application's RBAC layer reasons about.
 *
 * <p>Each constant maps to a row or rule in SPEC §1's three-tier
 * permission matrix (the locked role model from SPEC §2). The
 * enum is intentionally narrow — it's the smallest set of
 * actions that lets feature code ask "can this role do this
 * thing?" without resorting to a stringly-typed check.
 *
 * <p>New endpoints / actions: add a constant here and update
 * the {@code Rbac.canAccess} switch. The {@code RbacTest}
 * asserts every (Role, Action) cell, so adding a new
 * (role, action) pair will fail the build until the
 * authorization rule is decided and a test is added.
 */
public enum Action {

    // ---- Application management (SPEC §1 row "Can Create" + "Can Manage") ----
    /**
     * Create a new application record. SYS_ADMIN only — Admin
     * and User cannot add new applications to the managed set.
     */
    CREATE_APPLICATION,

    /**
     * Update an existing application's metadata (name, IP, SSH
     * credentials, scripts, poll interval). Locked while the
     * application is RUNNING/STARTING/STOPPING per SPEC §6.4;
     * the lock is enforced separately by the application form,
     * not here. SYS_ADMIN only.
     */
    UPDATE_APPLICATION,

    /**
     * Hard-delete an application. SPEC §3.3 hard-delete only.
     * Locked while the application is not STOPPED per SPEC §6.4.
     * SYS_ADMIN only.
     */
    DELETE_APPLICATION,

    /**
     * View the application list. The role gating here is
     * coarse — SYS_ADMIN and ADMIN see all, USER sees only
     * assigned applications. The "only assigned" filtering
     * happens in the list query (joining on
     * {@code user_application_assignments}); this enum value
     * only asks "is listing allowed at all?".
     */
    LIST_APPLICATIONS,

    /**
     * Read the full detail of one specific application. Same
     * coarse/fine split as LIST_APPLICATIONS — this action
     * gates "is access to one app allowed at all"; the
     * per-resource check is {@code Rbac.canAccessApplication}.
     */
    ACCESS_APPLICATION,

    // ---- Start / stop lifecycle (SPEC §6) ----
    /**
     * Start or stop an application. Allowed for any role that
     * has access to the specific application. The role-vs-action
     * gate is coarse; the per-resource check is the same
     * {@code canAccessApplication} function.
     */
    CONTROL_APPLICATION,

    // ---- User management (SPEC §1 rows "Can Create Users" + "Can Manage Users") ----
    /**
     * Create a new user. Per SPEC §1: SYS_ADMIN can create
     * Admins and Users; ADMIN can create Users only. The role
     * ceiling for the new account is enforced by the user-
     * creation service (and audited), not here — this action
     * just asks "is this caller allowed to create any user
     * at all?".
     */
    CREATE_USER,

    /**
     * Create a new Admin (or Sys.Admin). SYS_ADMIN only — the
     * "Admin ceiling" rule from SPEC §1 says Admin cannot
     * create/promote to Admin or Sys.Admin, so the only role
     * that can do this is SYS_ADMIN. Distinct from
     * {@link #CREATE_USER} so the gate can be enforced
     * independently at the controller / service level.
     */
    CREATE_ADMIN,

    /**
     * Update an existing user's role and/or assigned
     * applications. Same gating as CREATE_USER. The Admin
     * ceiling (ADMIN cannot promote to ADMIN/SYS_ADMIN) is
     * enforced by the user-update service.
     */
    UPDATE_USER,

    /**
     * Delete a user. Allowed for SYS_ADMIN and ADMIN. The
     * no-self-delete rule (SPEC §1) is enforced by the user-
     * delete service — this action just says "is this caller
     * allowed to delete users at all?".
     */
    DELETE_USER,

    /**
     * Assign a specific application to a user. Admin+ only.
     * USER cannot self-assign; that capability would defeat
     * the SPEC §1 row "Application Access" rule that says
     * USER access comes from admin assignment, not self-grant.
     */
    ASSIGN_APPLICATION,

    // ---- Audit log (SPEC §1 "Audit Log" column) ----
    /**
     * Read the audit log. Admin+ only — USER has no audit
     * visibility per the SPEC.
     */
    VIEW_AUDIT_LOG,

    // ---- Passwords (SPEC §12.2, §4.3 "change-password") ----
    /**
     * Admin-initiated password reset for another user
     * (returns a one-time temp password per SPEC §12.2).
     * Admin+ only.
     */
    RESET_PASSWORD,

    /**
     * Self-service change of one's own password. Allowed for
     * any role. Distinct from RESET_PASSWORD because the
     * audit/notification flow is different — this is a
     * user-initiated action, not an admin-initiated one.
     */
    CHANGE_OWN_PASSWORD
}
