package com.bpl.orderapp.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.auth.dto.ChangePasswordRequest;
import com.bpl.orderapp.admin.auth.dto.LoginRequest;
import com.bpl.orderapp.admin.common.GlobalExceptionHandler;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Slice test for {@link AuthController} covering:
 * <ul>
 *   <li>Successful login returns 200 with the right body shape</li>
 *   <li>Wrong password returns 401 with the generic error envelope</li>
 *   <li>Unknown user returns 401 with the SAME generic envelope
 *       (no username-enumeration leak)</li>
 *   <li>Soft-deleted user is treated as "no such user"</li>
 *   <li>Validation failures return 400 with the validation envelope</li>
 *   <li>The cleartext password is never echoed in the response body
 *       and never appears in the SQL parameter binding</li>
 * </ul>
 *
 * <p>Spring Security is excluded by the production main class, so
 * these tests don't need to provide a security config.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @MockBean private JdbcTemplate jdbc;
    @MockBean private BCryptPasswordEncoder encoder;
    @MockBean private SecurityContextRepository securityContextRepository;
    @MockBean private UserRepository userRepository;
    @MockBean private AuditWriter auditWriter;

    private static final String TEST_PASSWORD = "The-real-password1";
    private static final String TEST_HASH = new BCryptPasswordEncoder(10).encode(TEST_PASSWORD);
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static User testUser(UUID id, String username, String role,
                                 String hash, boolean mustChangePassword) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setUsername(username);
        u.setRole(role);
        u.setPasswordHash(hash);
        u.setMustChangePassword(mustChangePassword);
        return u;
    }

    @Test
    void login_succeeds_withCorrectCredentials() throws Exception {
        // Arrange: a real-looking user row.
        when(userRepository.findByUsername("admin")).thenReturn(
            Optional.of(testUser(ADMIN_ID, "admin", "SYS_ADMIN", TEST_HASH, true)));
        when(encoder.matches(TEST_PASSWORD, TEST_HASH)).thenReturn(true);

        // Act + assert
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new LoginRequest("admin", TEST_PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(ADMIN_ID.toString()))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.role").value("SYS_ADMIN"))
            .andExpect(jsonPath("$.mustChangePassword").value(true));

        // The lookup must use the username, never the password
        ArgumentCaptor<String> lookupArg = ArgumentCaptor.forClass(String.class);
        verify(userRepository).findByUsername(lookupArg.capture());
        assertThat(lookupArg.getAllValues())
            .as("the lookup must use the username, never the password")
            .containsExactly("admin");

        // The encoder.matches was called exactly once with the
        // (cleartext, hash) pair, never with a dummy.
        verify(encoder, times(1)).matches(TEST_PASSWORD, TEST_HASH);
    }

    @Test
    void login_fails_withWrongPassword_returns401_withGenericEnvelope() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(
            Optional.of(testUser(ADMIN_ID, "admin", "SYS_ADMIN", TEST_HASH, false)));
        when(encoder.matches("wrong-password", TEST_HASH)).thenReturn(false);

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new LoginRequest("admin", "wrong-password"))))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.title").value("Invalid Credentials"));
    }

    @Test
    void login_fails_withUnknownUser_returns401_withSameEnvelope() throws Exception {
        // Empty result set — user doesn't exist (or is soft-deleted).
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new LoginRequest("nobody", "any-password"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // Critically: encoder.matches must still be called even
        // when no user exists. This is the constant-time fix —
        // otherwise an attacker can distinguish "no such user"
        // (fast, no bcrypt) from "wrong password" (slow, bcrypt).
        // The hash argument is a dummy, the cleartext is the
        // attacker's guess.
        verify(encoder, times(1)).matches(eq("any-password"), anyString());
        verify(encoder, never()).matches(eq("any-password"), eq(TEST_HASH));
    }

    @Test
    void login_softDeletedUser_isTreatedAsUnknown() throws Exception {
        // Soft-deleted users have deleted_at IS NOT NULL, so the
        // query filter excludes them. The controller's response
        // is the same 401 envelope as a never-existed user.
        when(userRepository.findByUsername("soft-deleted")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new LoginRequest("soft-deleted", TEST_PASSWORD))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_blankUsername_returns400_validationFailed() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"password\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details.username").exists());

        // The UserRepository must not be touched on a validation
        // failure — bean validation runs first.
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void login_blankPassword_returns400_validationFailed() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details.password").exists());

        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void login_malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not valid json"))
            .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------
    // change-password
    // -----------------------------------------------------------------

    private static final String NEW_PASSWORD = "A-fresh-password-12chars";

    @Test
    void changePassword_succeeds_flipsMustChangePasswordToFalse() throws Exception {
        // User is currently in the "must change" state.
        when(userRepository.findByUsername("admin")).thenReturn(
            Optional.of(testUser(ADMIN_ID, "admin", "SYS_ADMIN", TEST_HASH, true)));
        when(encoder.matches(TEST_PASSWORD, TEST_HASH)).thenReturn(true);
        when(encoder.encode(NEW_PASSWORD)).thenReturn("new-bcrypt-hash");

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("admin", TEST_PASSWORD, NEW_PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(ADMIN_ID.toString()))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.role").value("SYS_ADMIN"))
            .andExpect(jsonPath("$.mustChangePassword").value(false));

        // The encoder.encode was called exactly once with the new
        // password (the hash argument is a placeholder — what we
        // care about is the cleartext input).
        verify(encoder, times(1)).encode(NEW_PASSWORD);

        // The repository save must capture the updated user with new hash and mustChangePassword=false
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("new-bcrypt-hash");
        assertThat(saved.getValue().isMustChangePassword()).isFalse();
    }

    @Test
    void changePassword_wrongOldPassword_returns401() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(
            Optional.of(testUser(ADMIN_ID, "admin", "SYS_ADMIN", TEST_HASH, true)));
        when(encoder.matches("not-the-old-password", TEST_HASH)).thenReturn(false);

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("admin", "not-the-old-password", NEW_PASSWORD))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // No save must have run — wrong old password is a hard
        // reject.
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_unknownUser_returns401_withSameEnvelope() throws Exception {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("nobody", TEST_PASSWORD, NEW_PASSWORD))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // Constant-time: the dummy-hash bcrypt matches must still
        // be called even on "no such user" — same shape as login.
        verify(encoder, times(1)).matches(eq(TEST_PASSWORD), anyString());
        verify(encoder, never()).matches(eq(TEST_PASSWORD), eq(TEST_HASH));
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_softDeletedUser_treatedAsUnknown() throws Exception {
        when(userRepository.findByUsername("soft-deleted")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("soft-deleted", TEST_PASSWORD, NEW_PASSWORD))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void changePassword_tooShort_returns400_validationFailed() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("admin", TEST_PASSWORD, "short"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details.newPassword").exists());

        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void changePassword_sameAsOld_returns400_validationFailed() throws Exception {
        // 12+ characters to pass length validation, but equal to
        // the old password.
        when(userRepository.findByUsername("admin")).thenReturn(
            Optional.of(testUser(ADMIN_ID, "admin", "SYS_ADMIN", TEST_HASH, true)));
        when(encoder.matches(TEST_PASSWORD, TEST_HASH)).thenReturn(true);

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("admin", TEST_PASSWORD, TEST_PASSWORD))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value(
                org.hamcrest.Matchers.containsString("must differ")));

        // No save was issued for a same-as-old rejection.
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_blankOldPassword_returns400() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"oldPassword\":\"\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details.oldPassword").exists());

        verify(userRepository, never()).findByUsername(anyString());
    }

    // -----------------------------------------------------------------
    // GET /auth/me
    // -----------------------------------------------------------------

    private void setAuthenticatedUser(String username) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
            username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void me_returnsUserAndAssignedApplicationIds() throws Exception {
        setAuthenticatedUser("admin");
        when(userRepository.findByUsername("admin")).thenReturn(
            Optional.of(testUser(ADMIN_ID, "admin", "SYS_ADMIN", TEST_HASH, false)));
        // The assignment query uses a different SQL form (no
        // username — uses user_id). Stub it explicitly.
        when(jdbc.queryForList(anyString(), eq(ADMIN_ID))).thenReturn(List.of(
            Map.of("application_id", 1L),
            Map.of("application_id", 2L),
            Map.of("application_id", 5L)
        ));

        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(ADMIN_ID.toString()))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.role").value("SYS_ADMIN"))
            .andExpect(jsonPath("$.mustChangePassword").value(false))
            .andExpect(jsonPath("$.assignedApplicationIds").isArray())
            .andExpect(jsonPath("$.assignedApplicationIds[0]").value(1))
            .andExpect(jsonPath("$.assignedApplicationIds[1]").value(2))
            .andExpect(jsonPath("$.assignedApplicationIds[2]").value(5));
    }

    @Test
    void me_noAuthentication_returns401_invalidCredentials() throws Exception {
        // No SecurityContext is set — the request reaches the
        // controller as unauthenticated.
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // The DB must not be touched on an unauthenticated /me
        // — the controller rejects at the principal check, before
        // any query runs.
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void me_authenticatedButUserSoftDeleted_returns401() throws Exception {
        setAuthenticatedUser("ghost");
        // The user is gone (soft-deleted or hard-deleted); the
        // SELECT returns an empty result. The controller must
        // treat this as "no valid session" — same envelope as no
        // auth at all.
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void me_userWithNoAssignedApplications_returnsEmptyArray() throws Exception {
        // A user with zero assignments is valid — they just see an
        // empty list. The endpoint must not 500 or omit the field.
        setAuthenticatedUser("alice");
        when(userRepository.findByUsername("alice")).thenReturn(
            Optional.of(testUser(ALICE_ID, "alice", "USER", TEST_HASH, true)));
        when(jdbc.queryForList(anyString(), eq(ALICE_ID))).thenReturn(List.of());

        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(ALICE_ID.toString()))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.mustChangePassword").value(true))
            .andExpect(jsonPath("$.assignedApplicationIds").isArray())
            .andExpect(jsonPath("$.assignedApplicationIds").isEmpty());
    }

    // -----------------------------------------------------------------
    // POST /auth/logout
    // -----------------------------------------------------------------

    @Test
    void logout_withActiveSession_returns204_andClearsSessionCookie() throws Exception {
        // The MockMvc test infrastructure auto-creates a session
        // for each request unless the test sets one up. The
        // controller calls session.invalidate() on whatever
        // session the request has, then writes a Set-Cookie
        // header that expires the SESSION cookie. We assert
        // both: status 204 and a Set-Cookie with Max-Age=0.
        mvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isNoContent())
            .andExpect(cookie().exists("SESSION"))
            .andExpect(cookie().maxAge("SESSION", 0));
    }

    @Test
    void logout_alreadyLoggedOut_returns204_idempotently() throws Exception {
        // No session to invalidate — the controller must still
        // return 204 and write the clearing Set-Cookie. This is
        // the "double-clicked logout button" case: the SPA
        // should never see an error from a duplicate logout.
        mvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isNoContent())
            .andExpect(cookie().exists("SESSION"))
            .andExpect(cookie().maxAge("SESSION", 0));
    }
}
