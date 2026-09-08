package com.bpl.orderapp.admin.security;

import com.bpl.orderapp.admin.common.Role;

import java.util.Collection;

/**
 * RBAC decision functions for SPEC §1's three-tier role model.
 *
 * <p>This is a pure utility — no Spring dependencies, no DB
 * calls, no I/O. It can be invoked from a controller, a
 * security expression, a test, or a service. The function is
 * the single source of truth for "can role X do action Y?";
 * duplicating this logic at any other layer is an anti-pattern
 * (see the {@code rbac-and-engine-assignment} skill).
 *
 * <h2>Two functions, two layers</h2>
 * <ul>
 *   <li>{@link #canAccess(Role, Action)} — coarse role-vs-action
 *       gate. The answer is the same regardless of which
 *       specific resource is in play. "Can a USER do
 *       {@code UPDATE_APPLICATION}?" is always false; no need
 *       to look at any assignment list.</li>
 *   <li>{@link #canAccessApplication(Role, Collection, Long)} —
 *       per-resource gate. The answer depends on the user's
 *       assigned application IDs. "Can THIS user access
 *       application 5?" is false if they aren't assigned to it
 *       (for USER) but true regardless of the id (for
 *       SYS_ADMIN/ADMIN).</li>
 * </ul>
 *
 * <h2>What this class does NOT do</h2>
 * <ul>
 *   <li>Self-delete enforcement — the Admin ceiling / no-self-
 *       delete rules are about specific resources (a user
 *       trying to delete themselves), not role-vs-action. The
 *       user-delete service is the right place for that check;
 *       this class just answers "is this caller allowed to
 *       delete users in general?".</li>
 *   <li>Audit — every state-changing action still writes an
 *       audit row per the {@code audit-log-coverage} skill;
 *       RBAC is the gate, audit is the record.</li>
 *   <li>The "Admin can only create Users, not other Admins"
 *       ceiling. That's a rule on the target role, not the
 *       caller's role — it's a service-layer concern (the
 *       user-creation service checks the new user's role
 *       against the caller's ceiling).</li>
 * </ul>
 */
public final class Rbac {

    private Rbac() {
        // utility class
    }

    /**
     * Coarse role-vs-action gate. Returns {@code true} iff the
     * role is allowed to perform the action at all (regardless
     * of which specific resource is involved).
     *
     * <p>The full permission matrix — every (Role, Action) cell —
     * is implemented as a single switch. Each case has a
     * comment citing the SPEC §1 row or §1 rule it implements,
     * so an audit reading this file can verify the rules
     * without cross-referencing a separate doc.
     *
     * @param role   the caller's role tier; never null
     * @param action the action being checked; never null
     * @return true iff the role is allowed to perform the
     *         action at all
     * @throws IllegalArgumentException if either argument is null
     */
    public static boolean canAccess(Role role, Action action) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        switch (role) {
            case SYS_ADMIN:
                // SPEC §1 row "Sys.Admin" — full access. SYS_ADMIN
                // can do every action in the matrix, including
                // actions that no other role can do (CREATE_APPLICATION,
                // CREATE_ADMIN, DELETE_APPLICATION).
                return true;

            case ADMIN:
                // SPEC §1 row "Admin" — full user and application
                // access, but cannot create Admins (the
                // "Admin ceiling" rule) and cannot create new
                // applications (Admin is for managing users, not
                // for adding new managed systems). Per the SPEC's
                // "Application Access" column, Admin sees all
                // applications AND can start/stop them (the
                // matrix conflates "see" with "operate" — for the
                // purposes of this coarse gate, both are true).
                return switch (action) {
                    // Application management: Admin cannot
                    // create / update / delete / hard-delete
                    // application records. But Admin CAN start
                    // and stop them (CONTROL_APPLICATION), per
                    // the "All applications" entry in the SPEC
                    // matrix. The lock against editing a running
                    // app is enforced separately by the form /
                    // service layer.
                    case CREATE_APPLICATION,
                         UPDATE_APPLICATION,
                         DELETE_APPLICATION -> false;
                    case CONTROL_APPLICATION,
                         LIST_APPLICATIONS,
                         ACCESS_APPLICATION -> true;
                    // User management: Admin can do all of it.
                    case CREATE_USER,
                         UPDATE_USER,
                         DELETE_USER,
                         ASSIGN_APPLICATION -> true;
                    // Admin ceiling: Admin cannot create Admins
                    // (or Sys.Admins). The CREATE_ADMIN action
                    // is SYS_ADMIN-only.
                    case CREATE_ADMIN -> false;
                    // Audit log: Admin sees the full audit log.
                    case VIEW_AUDIT_LOG -> true;
                    // Passwords: Admin can reset any user's
                    // password, and can change their own.
                    case RESET_PASSWORD,
                         CHANGE_OWN_PASSWORD -> true;
                };

            case USER:
                // SPEC §1 row "User" — no Create, no Manage,
                // Application Access = "Assigned applications
                // only", Audit Log = "None". Translated to
                // actions:
                return switch (action) {
                    // No application management.
                    case CREATE_APPLICATION,
                         UPDATE_APPLICATION,
                         DELETE_APPLICATION -> false;
                    // User can see their application list and
                    // access individual applications — but only
                    // the ones they're assigned to. The coarse
                    // gate here is "yes, listing is allowed for
                    // users at all"; the per-resource check is
                    // canAccessApplication, applied per row in
                    // the list query and per detail request.
                    case LIST_APPLICATIONS,
                         ACCESS_APPLICATION -> true;
                    // User can start/stop applications they're
                    // assigned to. Same coarse-vs-fine split.
                    case CONTROL_APPLICATION -> true;
                    // No user management.
                    case CREATE_USER,
                         UPDATE_USER,
                         DELETE_USER,
                         ASSIGN_APPLICATION,
                         CREATE_ADMIN -> false;
                    // No audit log visibility.
                    case VIEW_AUDIT_LOG -> false;
                    // User cannot reset other users'
                    // passwords (that's an admin action). User
                    // CAN change their own password — that's the
                    // forced change-password flow from SPEC
                    // §8.1, and any user-initiated change.
                    case RESET_PASSWORD -> false;
                    case CHANGE_OWN_PASSWORD -> true;
                };

            default:
                // Unreachable today (the enum has only three
                // values) but keeps the compiler honest and
                // the function safe to extend.
                throw new IllegalStateException(
                    "Unhandled role: " + role);
        }
    }

    /**
     * Per-resource check for application access. Implements the
     * SPEC §1 "Application Access" column:
     * <ul>
     *   <li>SYS_ADMIN: all applications</li>
     *   <li>ADMIN: all applications</li>
     *   <li>USER: assigned applications only</li>
     * </ul>
     *
     * <p>Per the {@code rbac-and-engine-assignment} skill, this
     * is the function backing Spring Security's
     * {@code canAccessApplication(id)} expression method
     * (SPEC §8.2) — when a Spring security filter chain lands,
     * the expression handler will call this function.
     *
     * <p>The {@code assignedApplicationIds} collection is the
     * user's assignments. For SYS_ADMIN and ADMIN, the
     * collection is ignored (they always have access). For USER,
     * the applicationId must be in the collection.
     *
     * @param role                    the caller's role tier
     * @param assignedApplicationIds the caller's assigned
     *                                 application IDs; for
     *                                 SYS_ADMIN/ADMIN this is
     *                                 unused but must not be null
     *                                 (pass an empty list if
     *                                 unknown)
     * @param applicationId           the application being
     *                                 accessed
     * @return true iff the caller is allowed to access this
     *         specific application
     */
    public static boolean canAccessApplication(
            Role role,
            Collection<Long> assignedApplicationIds,
            Long applicationId) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (assignedApplicationIds == null) {
            throw new IllegalArgumentException(
                "assignedApplicationIds must not be null (use an empty list if unknown)");
        }
        if (applicationId == null) {
            throw new IllegalArgumentException("applicationId must not be null");
        }
        switch (role) {
            case SYS_ADMIN:
            case ADMIN:
                // Both tiers see all applications per SPEC §1.
                return true;
            case USER:
                // The user must be assigned to the specific
                // application. Linear scan is fine for the
                // expected assignment count (handful of apps
                // per user); if the assignment list grows large,
                // a Set lookup would be the right next step.
                return assignedApplicationIds.contains(applicationId);
            default:
                throw new IllegalStateException("Unhandled role: " + role);
        }
    }
}
