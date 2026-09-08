package com.bpl.orderapp.admin.accountDeletion;

/**
 * Thrown when a user attempts to delete their own account.
 *
 * <p>SPEC §2 "Rules" — "No self-deletion: no role can delete
 * their own account." This rule applies uniformly to every role
 * tier (SYS_ADMIN, ADMIN, USER); the per-role check is not the
 * point — the self/non-self distinction is. The thrown error code
 * is {@code SELF_DELETE_FORBIDDEN} per SPEC §4.2, mapped to HTTP
 * 403 by {@code GlobalExceptionHandler}.
 *
 * <p>This is intentionally a distinct exception from
 * {@code Rbac.canAccess(...)}'s boolean return style: the
 * "no self-deletion" rule is a per-resource invariant (a user
 * trying to delete a specific user — themselves) rather than a
 * role-vs-action gate. Throwing an exception at the rule's
 * enforcement site is the natural way to surface the violation
 * with its error code; the alternative — a boolean return —
 * would push the error-mapping code to every call site.
 */
public class SelfDeleteForbiddenException extends RuntimeException {

    public SelfDeleteForbiddenException(Long callerUserId, Long targetUserId) {
        super(
            "Self-deletion is forbidden (caller id=" + callerUserId
                + ", target id=" + targetUserId + ")"
        );
    }
}
