package com.bpl.orderapp.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;

import com.bpl.orderapp.admin.common.GlobalExceptionHandler;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired private MockMvc mvc;

    @MockBean private JdbcTemplate jdbc;
    @MockBean private BCryptPasswordEncoder encoder;

    private static final UUID TEST_USER_ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

    @Test
    void resetPassword_succeeds_generatesTempPasswordAndFlipsFlag() throws Exception {
        when(jdbc.queryForList(anyString(), eq(TEST_USER_ID))).thenReturn(List.of(
            Map.of("id", TEST_USER_ID, "username", "alice")
        ));
        when(encoder.encode(anyString())).thenReturn("new-bcrypt-hash-for-temp");
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        mvc.perform(post("/api/v1/users/" + TEST_USER_ID + "/reset-password"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.temporaryPassword").exists())
            .andExpect(jsonPath("$.temporaryPassword").isString());

        ArgumentCaptor<String> cleartextCaptor = ArgumentCaptor.forClass(String.class);
        verify(encoder, times(1)).encode(cleartextCaptor.capture());
        String generated = cleartextCaptor.getValue();
        assertThat(generated).hasSize(32);
        assertThat(generated).matches("^[A-Za-z0-9_-]+$");

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.UUID> idCaptor = ArgumentCaptor.forClass(java.util.UUID.class);
        verify(jdbc).update(anyString(), hashCaptor.capture(), any(), idCaptor.capture());
        assertThat(hashCaptor.getValue()).isEqualTo("new-bcrypt-hash-for-temp");
        assertThat(idCaptor.getValue()).isEqualTo(TEST_USER_ID);
        assertThat(hashCaptor.getValue()).isNotEqualTo(generated);
    }

    @Test
    void resetPassword_userNotFound_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(missing))).thenReturn(List.of());

        mvc.perform(post("/api/v1/users/" + missing + "/reset-password"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void resetPassword_softDeletedUser_treatedAsNotFound() throws Exception {
        UUID deleted = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(deleted))).thenReturn(List.of());

        mvc.perform(post("/api/v1/users/" + deleted + "/reset-password"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }
}
