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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.GlobalExceptionHandler;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired private MockMvc mvc;

    @MockBean private JdbcTemplate jdbc;
    @MockBean private BCryptPasswordEncoder encoder;
    @MockBean private UserRepository userRepository;
    @MockBean private AuditWriter auditWriter;

    private static final UUID TEST_USER_ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

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

    @Test
    void resetPassword_userNotFound_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        when(userRepository.findById(missing)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/users/" + missing + "/reset-password"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_softDeletedUser_treatedAsNotFound() throws Exception {
        UUID deleted = UUID.randomUUID();
        when(userRepository.findById(deleted)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/users/" + deleted + "/reset-password"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(userRepository, never()).save(any());
    }
}
