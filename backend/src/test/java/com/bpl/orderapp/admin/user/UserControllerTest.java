package com.bpl.orderapp.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import com.bpl.orderapp.admin.websocket.WebSocketConnectionRepository;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.common.GlobalExceptionHandler;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired private MockMvc mvc;

    @MockBean private FindByIndexNameSessionRepository<?> sessionRepository;
    @MockBean private BCryptPasswordEncoder encoder;
    @MockBean private UserRepository userRepository;
    @MockBean private AuditWriter auditWriter;
    @MockBean private ApplicationRepository applicationRepository;
    @MockBean private UserApplicationAssignmentRepository assignmentRepository;
    @MockBean private WebSocketConnectionRepository connectionRepository;

    private static final UUID TEST_USER_ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

    @BeforeEach
    void loginAsSysAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SYS_ADMIN"))));
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resetPassword_succeeds_generatesTempPasswordAndFlipsFlag() throws Exception {
        User alice = new User();
        ReflectionTestUtils.setField(alice, "id", TEST_USER_ID);
        alice.setUsername("alice");
        alice.setRole("USER");
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(alice));
        when(encoder.encode(anyString())).thenReturn("new-bcrypt-hash-for-temp");

        mvc.perform(post("/api/v1/users/" + TEST_USER_ID + "/reset-password"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.temporaryPassword").exists())
            .andExpect(jsonPath("$.temporaryPassword").isString());

        ArgumentCaptor<String> cleartextCaptor = ArgumentCaptor.forClass(String.class);
        verify(encoder, times(1)).encode(cleartextCaptor.capture());
        String generated = cleartextCaptor.getValue();
        assertThat(generated).hasSize(32);
        assertThat(generated).matches("^[A-Za-z0-9_-]+$");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("new-bcrypt-hash-for-temp");
        assertThat(saved.getValue().isMustChangePassword()).isTrue();
    }

    private User aliceWithLiveSessions() {
        User alice = new User();
        ReflectionTestUtils.setField(alice, "id", TEST_USER_ID);
        alice.setUsername("alice");
        alice.setRole("ADMIN");
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(alice));
        org.mockito.Mockito.doReturn(java.util.Map.of(
                "s1", org.mockito.Mockito.mock(org.springframework.session.Session.class),
                "s2", org.mockito.Mockito.mock(org.springframework.session.Session.class)))
            .when(sessionRepository).findByPrincipalName("alice");
        return alice;
    }

    @Test
    void resetPassword_revokesAllOfTheUsersSessions() throws Exception {
        aliceWithLiveSessions();
        when(encoder.encode(anyString())).thenReturn("hash");

        mvc.perform(post("/api/v1/users/" + TEST_USER_ID + "/reset-password"))
            .andExpect(status().isOk());

        verify(sessionRepository).deleteById("s1");
        verify(sessionRepository).deleteById("s2");
    }

    @Test
    void updateUser_roleChange_revokesTheUsersSessions() throws Exception {
        aliceWithLiveSessions();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/users/" + TEST_USER_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}"))
            .andExpect(status().isOk());

        verify(sessionRepository).deleteById("s1");
        verify(sessionRepository).deleteById("s2");
    }

    // The DB no longer has a CHECK on users.role (SQLite migration), so the
    // controller is the only guard against storing an unknown role.
    @Test
    void updateUser_unknownRole_isRejectedAndNothingIsSaved() throws Exception {
        aliceWithLiveSessions();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/users/" + TEST_USER_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"role\":\"BOGUS\"}"))
            .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never())
            .save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void updateUser_emailOnlyChange_keepsTheUsersSessions() throws Exception {
        aliceWithLiveSessions();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/users/" + TEST_USER_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\"}"))
            .andExpect(status().isOk());

        verify(sessionRepository, never()).deleteById(anyString());
    }

    @Test
    void resetPassword_userNotFound_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        when(userRepository.findById(missing)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/users/" + missing + "/reset-password"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(userRepository, never()).save(any());
    }
}
