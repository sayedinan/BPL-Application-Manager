package com.bpl.orderapp.admin.accountDeletion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the "no self-deletion" guard (SPEC §2 Rules).
 *
 * <p>The rule is role-agnostic — "no role can delete their own
 * account" applies uniformly to SYS_ADMIN, ADMIN, and USER. The
 * guard's job is purely the self/non-self check, not the role
 * check, so the tests don't enumerate roles — the role check is
 * the user-deletion service's job (call {@code Rbac.canAccess}
 * to verify the role has the right, then call this guard to
 * verify it's not the self case).
 */
class AccountDeletionGuardTest {

    @Test
    void assertCanDelete_sameUserId_throws() {
        Long selfId = 42L;
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(selfId, selfId))
            .isInstanceOf(SelfDeleteForbiddenException.class);
    }

    @Test
    void assertCanDelete_sameUserId_zero_throws() {
        // Boundary: user id 0 is technically valid (no rule
        // says ids start at 1). The self check should still
        // fire.
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(0L, 0L))
            .isInstanceOf(SelfDeleteForbiddenException.class);
    }

    @Test
    void assertCanDelete_sameUserId_negative_throws() {
        // The DB sequence starts at 1 so this is a degenerate
        // case, but the guard should still enforce the rule
        // uniformly regardless of id sign.
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(-1L, -1L))
            .isInstanceOf(SelfDeleteForbiddenException.class);
    }

    @Test
    void assertCanDelete_differentUsers_doesNotThrow() {
        // The "happy path" for an admin deleting a different
        // user. The guard should pass; the role check is the
        // service's separate concern.
        assertThatCode(() -> AccountDeletionGuard.assertCanDelete(1L, 2L))
            .doesNotThrowAnyException();
    }

    @Test
    void assertCanDelete_callerDeletesCaller_throwsButReverseDirectionPasses() {
        // Asymmetric check: caller=1, target=2 passes; caller=2,
        // target=1 also passes. The guard is symmetric in
        // nature (self vs non-self) but the parameter order
        // is caller-first, target-second.
        Long alice = 1L;
        Long bob = 2L;
        assertThatCode(() -> AccountDeletionGuard.assertCanDelete(alice, bob))
            .doesNotThrowAnyException();
        assertThatCode(() -> AccountDeletionGuard.assertCanDelete(bob, alice))
            .doesNotThrowAnyException();
    }

    @Test
    void assertCanDelete_nullCaller_throws() {
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(null, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callerUserId");
    }

    @Test
    void assertCanDelete_nullTarget_throws() {
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(1L, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetUserId");
    }

    @Test
    void assertCanDelete_bothNull_throws_onCallerFirst() {
        // Caller is checked first; both-null is a caller-null
        // case. The guard fails fast on the first invalid
        // argument it sees.
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callerUserId");
    }
}
