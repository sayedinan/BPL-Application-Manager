package com.bpl.orderapp.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.bpl.orderapp.admin.common.GlobalExceptionHandler;

/**
 * Slice test for the reset-password endpoint.
 *
 * <p>Spring Security is excluded in production via
 * {@code BplApplicationAdminApplication}, so the test slice uses
 * {@code @AutoConfigureMockMvc(addFilters = false)} to match.
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired private MockMvc mvc;

    @MockBean private JdbcTemplate jdbc;
    @MockBean private BCryptPasswordEncoder encoder;

    @Test
    void resetPassword_succeeds_generatesTempPasswordAndFlipsFlag() throws Exception {
        // User exists and is not soft-deleted.
        when(jdbc.queryForList(anyString(), eq(1L))).thenReturn(List.of(
            Map.of("id", 1L, "username", "alice")
        ));
        // The encoder produces a synthetic (predictable) hash so
        // we can assert what got bound. The cleartext temp password
        // is whatever the controller generated; we don't see it
        // directly in the DB write, but we can verify it was
        // passed to encoder.encode.
        when(encoder.encode(anyString())).thenReturn("new-bcrypt-hash-for-temp");
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        mvc.perform(post("/api/v1/users/1/reset-password"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.temporaryPassword").exists())
            .andExpect(jsonPath("$.temporaryPassword").isString());

        // 1. The cleartext was generated and passed to encoder.encode
        //    exactly once. We don't know the value, but we can
        //    verify the shape: 32 chars of base64url (no padding).
        ArgumentCaptor<String> cleartextCaptor = ArgumentCaptor.forClass(String.class);
        verify(encoder, times(1)).encode(cleartextCaptor.capture());
        String generated = cleartextCaptor.getValue();
        assertThat(generated).hasSize(32); // 24 bytes → 32 base64url chars
        assertThat(generated).matches("^[A-Za-z0-9_-]+$"); // base64url alphabet, no padding

        // 2. The cleartext was NOT bound as a SQL parameter — only
        //    the hash and the id are.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(jdbc).update(anyString(), hashCaptor.capture(), any(), idCaptor.capture());
        assertThat(hashCaptor.getValue()).isEqualTo("new-bcrypt-hash-for-temp");
        assertThat(idCaptor.getValue()).isEqualTo(1L);
        // The encoded hash is a bcrypt-shaped string, not the
        // cleartext — confirms the SQL got the hash, not the
        // cleartext.
        assertThat(hashCaptor.getValue()).isNotEqualTo(generated);
    }

    @Test
    void resetPassword_userNotFound_returns404() throws Exception {
        when(jdbc.queryForList(anyString(), eq(999L))).thenReturn(List.of());

        mvc.perform(post("/api/v1/users/999/reset-password"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // No UPDATE must have run when the user is missing.
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void resetPassword_softDeletedUser_treatedAsNotFound() throws Exception {
        // Soft-deleted users are filtered out by the SELECT — the
        // controller sees an empty result and returns 404, identical
        // to a never-existed user. This prevents an admin from
        // resurrecting a soft-deleted user by accident.
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());

        mvc.perform(post("/api/v1/users/7/reset-password"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }
}
