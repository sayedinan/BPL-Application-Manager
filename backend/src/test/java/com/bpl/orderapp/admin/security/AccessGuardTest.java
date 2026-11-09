package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import com.bpl.orderapp.admin.common.AccessDeniedAppException;
import com.bpl.orderapp.admin.common.InvalidCredentialsException;
import com.bpl.orderapp.admin.common.Role;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class AccessGuardTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private boolean userExists = true;
    private String role = "SYS_ADMIN";
    private List<Long> assignedApplications = List.of();

    private final UserRepository userRepository = mock(UserRepository.class, this::fakeUsers);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, this::fakeDatabase);
    private final AccessGuard guard = new AccessGuard(jdbc, userRepository);

    // Answers findByUsername from the same userExists / role fields the
    // tests already toggle, so the individual tests don't change.
    private Object fakeUsers(InvocationOnMock call) {
        if (!call.getMethod().getName().equals("findByUsername")) {
            return null;
        }
        if (!userExists) {
            return Optional.empty();
        }
        User u = new User();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setRole(role);
        return Optional.of(u);
    }

    private Object fakeDatabase(InvocationOnMock call) {
        String sql = call.getArgument(0);
        if (sql.startsWith("SELECT uaa.application_id")) {
            return assignedApplications;
        }
        return null;
    }

    private void signInAs(String username, String roleName) {
        this.role = roleName;
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theCaller_carriesNameRoleAndId() {
        signInAs("alice", "ADMIN");
        AccessGuard.Caller caller = guard.currentCaller();
        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.role()).isEqualTo(Role.ADMIN);
        assertThat(caller.userId()).isEqualTo(USER_ID);
    }

    @Test
    void noSession_isRejected() {
        assertThatThrownBy(() -> guard.require(Action.LIST_APPLICATIONS))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aSessionForAUserWhoNoLongerExists_isRejected() {
        signInAs("ghost", "USER");
        userExists = false;
        assertThatThrownBy(() -> guard.require(Action.LIST_APPLICATIONS))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void sysAdmin_mayCreateAndAccessApplications() {
        signInAs("root", "SYS_ADMIN");
        assertThat(guard.require(Action.CREATE_APPLICATION).role()).isEqualTo(Role.SYS_ADMIN);
        assertThat(guard.require(Action.ACCESS_APPLICATION).username()).isEqualTo("root");
    }

    @Test
    void admin_isRefusedTheSysAdminOnlyActions() {
        signInAs("alice", "ADMIN");
        for (Action action : List.of(
                Action.CREATE_APPLICATION, Action.UPDATE_APPLICATION,
                Action.DELETE_APPLICATION, Action.ACCESS_APPLICATION)) {
            assertThatThrownBy(() -> guard.require(action))
                .as("ADMIN must not be allowed to %s", action)
                .isInstanceOf(AccessDeniedAppException.class);
        }
    }

    @Test
    void user_isRefusedAdministration_butMayListApplications() {
        signInAs("bob", "USER");
        assertThat(guard.require(Action.LIST_APPLICATIONS).role()).isEqualTo(Role.USER);
        assertThatThrownBy(() -> guard.require(Action.CREATE_APPLICATION))
            .isInstanceOf(AccessDeniedAppException.class);
    }

    @Test
    void admin_mayControlAnyApplication() {
        signInAs("alice", "ADMIN");
        assertThat(guard.requireApplication(Action.CONTROL_APPLICATION, 99L).username()).isEqualTo("alice");
    }

    @Test
    void user_mayControlOnlyTheApplicationsAssignedToThem() {
        signInAs("bob", "USER");
        assignedApplications = List.of(5L);
        assertThat(guard.requireApplication(Action.CONTROL_APPLICATION, 5L).username()).isEqualTo("bob");
        assertThatThrownBy(() -> guard.requireApplication(Action.CONTROL_APPLICATION, 6L))
            .isInstanceOf(AccessDeniedAppException.class);
    }

    @Test
    void user_withNoAssignments_mayControlNothing() {
        signInAs("bob", "USER");
        assertThatThrownBy(() -> guard.requireApplication(Action.CONTROL_APPLICATION, 5L))
            .isInstanceOf(AccessDeniedAppException.class);
    }
}
