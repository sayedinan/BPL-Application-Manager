package com.bpl.orderapp.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.bpl.orderapp.admin.auth.dto.ChangePasswordRequest;
import com.bpl.orderapp.admin.auth.dto.LoginRequest;
import com.bpl.orderapp.admin.common.GlobalExceptionHandler;
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

    private static final String TEST_PASSWORD = "the-real-password";
    private static final String TEST_HASH = new BCryptPasswordEncoder(10).encode(TEST_PASSWORD);

    @Test
    void login_succeeds_withCorrectCredentials() throws Exception {
        // Arrange: a real-looking user row.
        when(jdbc.queryForList(anyString(), eq("admin"))).thenReturn(List.of(
            Map.of(
                "id", 1L,
                "username", "admin",
                "role", "SYS_ADMIN",
                "password_hash", TEST_HASH,
                "must_change_password", true
            )
        ));
        when(encoder.matches(TEST_PASSWORD, TEST_HASH)).thenReturn(true);

        // Act + assert
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new LoginRequest("admin", TEST_PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.role").value("SYS_ADMIN"))
            .andExpect(jsonPath("$.mustChangePassword").value(true));

        // The cleartext password must never have been bound as a
        // SQL parameter — only the username is.
        ArgumentCaptor<Object> sqlArgs = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).queryForList(anyString(), sqlArgs.capture());
        assertThat(sqlArgs.getAllValues())
            .as("the SQL query must bind the username, never the password")
            .containsExactly("admin");

        // The encoder.matches was called exactly once with the
        // (cleartext, hash) pair, never with a dummy.
        verify(encoder, times(1)).matches(TEST_PASSWORD, TEST_HASH);
    }

    @Test
    void login_fails_withWrongPassword_returns401_withGenericEnvelope() throws Exception {
        when(jdbc.queryForList(anyString(), eq("admin"))).thenReturn(List.of(
            Map.of(
                "id", 1L,
                "username", "admin",
                "role", "SYS_ADMIN",
                "password_hash", TEST_HASH,
                "must_change_password", false
            )
        ));
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
        when(jdbc.queryForList(anyString(), eq("nobody"))).thenReturn(List.of());

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
        when(jdbc.queryForList(anyString(), eq("soft-deleted"))).thenReturn(List.of());

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

        // The JdbcTemplate must not be touched on a validation
        // failure — bean validation runs first.
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void login_blankPassword_returns400_validationFailed() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details.password").exists());

        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
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

    private static final String NEW_PASSWORD = "a-fresh-password-12chars";

    @Test
    void changePassword_succeeds_flipsMustChangePasswordToFalse() throws Exception {
        // User is currently in the "must change" state.
        when(jdbc.queryForList(anyString(), eq("admin"))).thenReturn(List.of(
            Map.of(
                "id", 1L,
                "username", "admin",
                "role", "SYS_ADMIN",
                "password_hash", TEST_HASH
            )
        ));
        when(encoder.matches(TEST_PASSWORD, TEST_HASH)).thenReturn(true);
        when(encoder.encode(NEW_PASSWORD)).thenReturn("new-bcrypt-hash");
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("admin", TEST_PASSWORD, NEW_PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.role").value("SYS_ADMIN"))
            .andExpect(jsonPath("$.mustChangePassword").value(false));

        // The encoder.encode was called exactly once with the new
        // password (the hash argument is a placeholder — what we
        // care about is the cleartext input).
        verify(encoder, times(1)).encode(NEW_PASSWORD);

        // The SQL update must bind the new hash + the user id. The
        // cleartext password is never bound as a SQL parameter.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> tsCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(jdbc).update(
            anyString(),
            hashCaptor.capture(),
            tsCaptor.capture(),
            idCaptor.capture()
        );
        assertThat(hashCaptor.getValue()).isEqualTo("new-bcrypt-hash");
        assertThat(idCaptor.getValue()).isEqualTo(1L);
    }

    @Test
    void changePassword_wrongOldPassword_returns401() throws Exception {
        when(jdbc.queryForList(anyString(), eq("admin"))).thenReturn(List.of(
            Map.of(
                "id", 1L,
                "username", "admin",
                "role", "SYS_ADMIN",
                "password_hash", TEST_HASH
            )
        ));
        when(encoder.matches("not-the-old-password", TEST_HASH)).thenReturn(false);

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("admin", "not-the-old-password", NEW_PASSWORD))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // No UPDATE must have run — wrong old password is a hard
        // reject.
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void changePassword_unknownUser_returns401_withSameEnvelope() throws Exception {
        when(jdbc.queryForList(anyString(), eq("nobody"))).thenReturn(List.of());

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
    }

    @Test
    void changePassword_softDeletedUser_treatedAsUnknown() throws Exception {
        when(jdbc.queryForList(anyString(), eq("soft-deleted"))).thenReturn(List.of());

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

        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void changePassword_sameAsOld_returns400_validationFailed() throws Exception {
        // 12+ characters to pass length validation, but equal to
        // the old password.
        when(jdbc.queryForList(anyString(), eq("admin"))).thenReturn(List.of(
            Map.of(
                "id", 1L,
                "username", "admin",
                "role", "SYS_ADMIN",
                "password_hash", TEST_HASH
            )
        ));
        when(encoder.matches(TEST_PASSWORD, TEST_HASH)).thenReturn(true);

        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ChangePasswordRequest("admin", TEST_PASSWORD, TEST_PASSWORD))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value(
                org.hamcrest.Matchers.containsString("must differ")));

        // No UPDATE was issued for a same-as-old rejection.
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void changePassword_blankOldPassword_returns400() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"oldPassword\":\"\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details.oldPassword").exists());

        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }
}
