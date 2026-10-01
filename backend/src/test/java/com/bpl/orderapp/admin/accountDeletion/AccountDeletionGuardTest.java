package com.bpl.orderapp.admin.accountDeletion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class AccountDeletionGuardTest {

    @Test
    void assertCanDelete_sameUserId_throws() {
        UUID selfId = UUID.randomUUID();
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(selfId, selfId))
            .isInstanceOf(SelfDeleteForbiddenException.class);
    }

    @Test
    void assertCanDelete_sameUserId_fixed_throws() {
        UUID fixed = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(fixed, fixed))
            .isInstanceOf(SelfDeleteForbiddenException.class);
    }

    @Test
    void assertCanDelete_differentUsers_doesNotThrow() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThatCode(() -> AccountDeletionGuard.assertCanDelete(a, b))
            .doesNotThrowAnyException();
    }

    @Test
    void assertCanDelete_callerDeletesCaller_throwsButReverseDirectionPasses() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertThatCode(() -> AccountDeletionGuard.assertCanDelete(alice, bob))
            .doesNotThrowAnyException();
        assertThatCode(() -> AccountDeletionGuard.assertCanDelete(bob, alice))
            .doesNotThrowAnyException();
    }

    @Test
    void assertCanDelete_nullCaller_throws() {
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(null, UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callerUserId");
    }

    @Test
    void assertCanDelete_nullTarget_throws() {
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(UUID.randomUUID(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetUserId");
    }

    @Test
    void assertCanDelete_bothNull_throws_onCallerFirst() {
        assertThatThrownBy(() -> AccountDeletionGuard.assertCanDelete(null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callerUserId");
    }
}
