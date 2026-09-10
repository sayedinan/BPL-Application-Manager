package com.bpl.orderapp.admin.accountDeletion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "No self-deletion" guard, per SPEC §2 "Rules".
 *
 * <p>SPEC §2: "No self-deletion — no role can delete their own
 * account." This rule applies uniformly to every role tier
 * (SYS_ADMIN, ADMIN, USER). It is a per-resource invariant
 * (the resource being the caller's own user record), not a
 * role-vs-action gate, so it lives outside the {@code Rbac}
 * utility — that class answers "can this role do this action
 * at all?"; this class answers "is this specific caller allowed
 * to delete this specific user?".
 *
 * <h2>Usage</h2>
 * Call this from the user-deletion service / controller BEFORE
 * any deletion work. If the call throws
 * {@link SelfDeleteForbiddenException}, the
 * {@code GlobalExceptionHandler} will turn it into a 403
 * with the {@code SELF_DELETE_FORBIDDEN} error code from
 * SPEC §4.2.
 *
 * <pre>{@code
 * public void deleteUser(Long callerUserId, Long targetUserId) {
 *     AccountDeletionGuard.assertCanDelete(callerUserId, targetUserId);
 *     // ... do the actual deletion
 * }
 * }</pre>
 *
 * <h2>What this guard does NOT do</h2>
 * <ul>
 *   <li>It does not check whether the caller has the role
 *       authority to delete users in general — that's
 *       {@code Rbac.canAccess(role, DELETE_USER)} (or
 *       {@code @PreAuthorize("hasAuthority... DELETE_USER")} when
 *       the security filter chain is wired). The two checks
 *       are orthogonal: a SYS_ADMIN can delete any user
 *       EXCEPT themselves; a USER cannot delete anyone,
 *       including themselves. The deletion service must run
 *       both checks.</li>
 *   <li>It does not check whether the user exists. A delete
 *       call with a non-existent targetUserId would be caught
 *       by the user-lookup query, not by this guard. This
 *       guard only enforces the self/non-self invariant.</li>
 *   <li>It does not log the attempted deletion. The audit
 *       log (per the {@code audit-log-coverage} skill) is
 *       the right place for that record, including
 *       SELF_DELETE_FORBIDDEN events; the audit infrastructure
 *       lands separately.</li>
 * </ul>
 */
public final class AccountDeletionGuard {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionGuard.class);

    private AccountDeletionGuard() {
        // utility class
    }

    /**
     * Asserts that the caller is allowed to delete the target
     * user. Throws {@link SelfDeleteForbiddenException} if
     * the caller is the target user (i.e. attempting self-
     * deletion). Returns normally in all other cases.
     *
     * <p>The check is purely the IDs: it does not consult the
     * database, does not check roles, does not check existence
     * of either user. It is the deletion service's job to
     * verify those other things; this guard enforces exactly
     * the self/non-self rule.
     *
     * @param callerUserId the user attempting the deletion
     *                      (typically the authenticated
     *                      principal's id); must not be null
     * @param targetUserId the user being deleted; must not be
     *                      null
     * @throws SelfDeleteForbiddenException if callerUserId ==
     *                                   targetUserId
     * @throws IllegalArgumentException    if either argument
     *                                   is null
     */
    public static void assertCanDelete(Long callerUserId, Long targetUserId) {
        if (callerUserId == null) {
            throw new IllegalArgumentException("callerUserId must not be null");
        }
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId must not be null");
        }
        if (callerUserId.equals(targetUserId)) {
            // Log at INFO (not WARN/ERROR) — a self-delete
            // attempt is a normal user error, not a system
            // anomaly. The audit log records the event when
            // audit infrastructure lands; this logger entry
            // exists only to aid in development / debugging.
            log.info(
                "Self-deletion rejected: caller id={} attempted to delete themselves",
                callerUserId
            );
            throw new SelfDeleteForbiddenException(callerUserId, targetUserId);
        }
    }
}
