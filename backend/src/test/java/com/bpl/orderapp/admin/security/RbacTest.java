package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.bpl.orderapp.admin.common.Role;

/**
 * Unit tests for {@link Rbac} — the role-vs-action and
 * role-vs-resource permission functions.
 *
 * <p>The test matrix below covers every (Role, Action) cell. If
 * a new {@link Action} is added, the compiler will not catch a
 * missing cell here, but a manual review of the {@code Rbac}
 * switch + this test class side-by-side is sufficient.
 */
class RbacTest {

    // -----------------------------------------------------------------
    // canAccess(Role, Action) — the (Role × Action) matrix
    // -----------------------------------------------------------------

    @Test
    void sysAdmin_canDoEverything() {
        // SPEC §1 row "Sys.Admin" — "Can Create: Applications,
        // Admins, Users", "Can Manage: All", "Application
        // Access: All applications", "Audit Log: Full". Every
        // action must return true.
        for (Action action : Action.values()) {
            assertThat(Rbac.canAccess(Role.SYS_ADMIN, action))
                .as("SYS_ADMIN must be able to %s", action)
                .isTrue();
        }
    }

    @Test
    void admin_cannotCreateApplications() {
        // SPEC §1 row "Admin" — "Can Create: Users" (not
        // Applications). Application create/update/delete are
        // out of scope for Admin.
        assertThat(Rbac.canAccess(Role.ADMIN, Action.CREATE_APPLICATION)).isFalse();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.UPDATE_APPLICATION)).isFalse();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.DELETE_APPLICATION)).isFalse();
    }

    @Test
    void admin_cannotCreateAdmins_adminCeilingRule() {
        // SPEC §1 "Admin ceiling" rule: "Admin cannot
        // create/promote to Admin or Sys.Admin". The CREATE_ADMIN
        // action is reserved for SYS_ADMIN only.
        assertThat(Rbac.canAccess(Role.ADMIN, Action.CREATE_ADMIN)).isFalse();
    }

    @Test
    void admin_canManageUsersAndSeeAllApplications() {
        // SPEC §1 row "Admin" — "Can Create: Users",
        // "Can Manage: Users", "Application Access: All
        // applications", "Audit Log: Full". All of these are
        // true.
        assertThat(Rbac.canAccess(Role.ADMIN, Action.CREATE_USER)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.UPDATE_USER)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.DELETE_USER)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.ASSIGN_APPLICATION)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.LIST_APPLICATIONS)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.ACCESS_APPLICATION)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.CONTROL_APPLICATION)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.VIEW_AUDIT_LOG)).isTrue();
        assertThat(Rbac.canAccess(Role.ADMIN, Action.RESET_PASSWORD)).isTrue();
    }

    @Test
    void admin_canChangeOwnPassword() {
        // The change-own-password action is universal — every
        // role can change their own password. Admin is the
        // interesting middle case.
        assertThat(Rbac.canAccess(Role.ADMIN, Action.CHANGE_OWN_PASSWORD)).isTrue();
    }

    @Test
    void user_hasNoUserManagementCapabilities() {
        // SPEC §1 row "User" — "Can Create: —", "Can Manage: —".
        // Every user-management action must be false.
        assertThat(Rbac.canAccess(Role.USER, Action.CREATE_USER)).isFalse();
        assertThat(Rbac.canAccess(Role.USER, Action.UPDATE_USER)).isFalse();
        assertThat(Rbac.canAccess(Role.USER, Action.DELETE_USER)).isFalse();
        assertThat(Rbac.canAccess(Role.USER, Action.ASSIGN_APPLICATION)).isFalse();
        assertThat(Rbac.canAccess(Role.USER, Action.CREATE_ADMIN)).isFalse();
    }

    @Test
    void user_hasNoApplicationManagementCapabilities() {
        // SPEC §1 row "User" — "Can Create: —", "Can Manage: —".
        // The coarse gate for create/update/delete is false.
        assertThat(Rbac.canAccess(Role.USER, Action.CREATE_APPLICATION)).isFalse();
        assertThat(Rbac.canAccess(Role.USER, Action.UPDATE_APPLICATION)).isFalse();
        assertThat(Rbac.canAccess(Role.USER, Action.DELETE_APPLICATION)).isFalse();
    }

    @Test
    void user_canAccessAndControlApplications_butPerAppGate() {
        // SPEC §1 row "User" — "Application Access: Assigned
        // applications only". The coarse gate is "yes, user can
        // access / control apps at all"; the per-resource check
        // (canAccessApplication) is what enforces "only the
        // assigned ones".
        assertThat(Rbac.canAccess(Role.USER, Action.LIST_APPLICATIONS)).isTrue();
        assertThat(Rbac.canAccess(Role.USER, Action.ACCESS_APPLICATION)).isTrue();
        assertThat(Rbac.canAccess(Role.USER, Action.CONTROL_APPLICATION)).isTrue();
    }

    @Test
    void user_cannotViewAuditLog() {
        // SPEC §1 row "User" — "Audit Log: None". Both
        // VIEW_AUDIT_LOG and the user-management readouts
        // (covered above) are out of scope.
        assertThat(Rbac.canAccess(Role.USER, Action.VIEW_AUDIT_LOG)).isFalse();
    }

    @Test
    void user_cannotResetOtherUsersPasswords_butCanChangeOwn() {
        // Admin-initiated reset is admin-only. Self-service
        // change-password is universal.
        assertThat(Rbac.canAccess(Role.USER, Action.RESET_PASSWORD)).isFalse();
        assertThat(Rbac.canAccess(Role.USER, Action.CHANGE_OWN_PASSWORD)).isTrue();
    }

    // -----------------------------------------------------------------
    // Argument validation
    // -----------------------------------------------------------------

    @Test
    void canAccess_nullRole_throws() {
        assertThatThrownBy(() -> Rbac.canAccess(null, Action.LIST_APPLICATIONS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role");
    }

    @Test
    void canAccess_nullAction_throws() {
        assertThatThrownBy(() -> Rbac.canAccess(Role.USER, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("action");
    }

    // -----------------------------------------------------------------
    // canAccessApplication — the per-resource check
    // -----------------------------------------------------------------

    @Test
    void sysAdmin_canAccessAnyApplication_regardlessOfAssignments() {
        // Even an empty assignment list doesn't restrict
        // SYS_ADMIN — per the SPEC §1 row "All applications".
        assertThat(Rbac.canAccessApplication(Role.SYS_ADMIN, List.of(), 1L)).isTrue();
        assertThat(Rbac.canAccessApplication(Role.SYS_ADMIN, List.of(99L), 1L)).isTrue();
        assertThat(Rbac.canAccessApplication(Role.SYS_ADMIN, List.of(1L, 2L, 3L), 999L)).isTrue();
    }

    @Test
    void admin_canAccessAnyApplication_regardlessOfAssignments() {
        // Same as SYS_ADMIN: Admin sees all applications.
        assertThat(Rbac.canAccessApplication(Role.ADMIN, List.of(), 1L)).isTrue();
        assertThat(Rbac.canAccessApplication(Role.ADMIN, List.of(99L), 1L)).isTrue();
    }

    @Test
    void user_canOnlyAccessAssignedApplications() {
        // SPEC §1 row "User" — "Assigned applications only".
        List<Long> userAssignments = List.of(1L, 3L, 5L);
        assertThat(Rbac.canAccessApplication(Role.USER, userAssignments, 1L)).isTrue();
        assertThat(Rbac.canAccessApplication(Role.USER, userAssignments, 3L)).isTrue();
        assertThat(Rbac.canAccessApplication(Role.USER, userAssignments, 5L)).isTrue();
        // Negative cases — not in the list.
        assertThat(Rbac.canAccessApplication(Role.USER, userAssignments, 2L)).isFalse();
        assertThat(Rbac.canAccessApplication(Role.USER, userAssignments, 4L)).isFalse();
    }

    @Test
    void user_withNoAssignments_cannotAccessAnything() {
        // An empty assignment list correctly denies access to
        // any application id.
        assertThat(Rbac.canAccessApplication(Role.USER, List.of(), 1L)).isFalse();
        assertThat(Rbac.canAccessApplication(Role.USER, List.of(), 999L)).isFalse();
    }

    @Test
    void canAccessApplication_nullRole_throws() {
        assertThatThrownBy(() -> Rbac.canAccessApplication(null, List.of(), 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role");
    }

    @Test
    void canAccessApplication_nullAssignments_throws() {
        // The assignments list is required to be non-null even
        // for SYS_ADMIN/ADMIN (who ignore it). This catches
        // call-site bugs that pass an undefined value.
        assertThatThrownBy(() -> Rbac.canAccessApplication(Role.SYS_ADMIN, null, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("assignedApplicationIds");
    }

    @Test
    void canAccessApplication_nullApplicationId_throws() {
        assertThatThrownBy(() -> Rbac.canAccessApplication(Role.USER, List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("applicationId");
    }

    // -----------------------------------------------------------------
    // Cross-check: every Action has a definite (true or false)
    // answer for every Role — no missing switch arms.
    // -----------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyActionHasADefiniteAnswerForSysAdmin(Role role) {
        // If the Rbac switch misses an arm for a (Role, Action)
        // pair, the function would throw IllegalStateException.
        // This test enumerates the cross product and confirms
        // the function doesn't throw — i.e. every cell is
        // covered.
        for (Action action : Action.values()) {
            // We don't assert the specific boolean here (that's
            // what the role-specific tests above do); we just
            // assert the function returns at all, which means
            // no arm is missing.
            boolean result = Rbac.canAccess(role, action);
            // Defensive: confirm the result is a real boolean
            // (not null, not a thrown exception escaping the
            // assert). If Rbac had a missing arm, this line
            // would throw before reaching the assertion.
            assertThat(result == true || result == false)
                .as("Rbac.canAccess(%s, %s) must return a boolean", role, action)
                .isTrue();
        }
    }

    @Test
    void actionEnum_coversEverythingTheSpecCallsFor() {
        // The Action enum should cover the SPEC §1 matrix's
        // implied action set. Adding a new SPEC requirement
        // without a corresponding Action constant would
        // silently allow the action. This test asserts the
        // current set is the locked one — if anyone changes it,
        // they have to update this assertion deliberately.
        Set<String> expected = Set.of(
            "CREATE_APPLICATION", "UPDATE_APPLICATION", "DELETE_APPLICATION",
            "LIST_APPLICATIONS", "ACCESS_APPLICATION", "CONTROL_APPLICATION",
            "CREATE_USER", "UPDATE_USER", "DELETE_USER", "ASSIGN_APPLICATION",
            "CREATE_ADMIN", "VIEW_AUDIT_LOG",
            "RESET_PASSWORD", "CHANGE_OWN_PASSWORD"
        );
        Set<String> actual = new java.util.HashSet<>();
        for (Action a : Action.values()) {
            actual.add(a.name());
        }
        assertThat(actual).isEqualTo(expected);
    }
}
