package com.bpl.orderapp.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.bpl.orderapp.admin.audit.AuditLogReader;
import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.IdempotencyService;
import com.bpl.orderapp.admin.common.Role;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.health.ApplicationHealthConfigRepository;
import com.bpl.orderapp.admin.log.ApplicationLogLineRepository;
import com.bpl.orderapp.admin.log.LogPollingOrchestrator;
import com.bpl.orderapp.admin.security.AccessGuard;
import com.bpl.orderapp.admin.ssh.SshConnection;
import com.bpl.orderapp.admin.status.ApplicationStatusStreakRepository;
import com.bpl.orderapp.admin.status.ApplicationStatusTransitionRepository;
import com.bpl.orderapp.admin.status.StatusPollingOrchestrator;
import com.bpl.orderapp.admin.user.UserRepository;

/** A start or stop whose script fails must still leave a FAILURE audit row. */
class StartStopFailureAuditTest {

    private static final long APP_ID = 7L;

    private final SshConnection ssh = mock(SshConnection.class);
    private final AuditWriter auditWriter = mock(AuditWriter.class);
    private final AccessGuard accessGuard = mock(AccessGuard.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final ApplicationHealthConfigRepository healthConfigs = mock(ApplicationHealthConfigRepository.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private ApplicationController controller;

    @BeforeEach
    void setUp() throws Exception {
        SshCredentialCipher cipher = mock(SshCredentialCipher.class);
        when(cipher.decrypt(any())).thenReturn("plain");
        when(accessGuard.currentCaller())
            .thenReturn(new AccessGuard.Caller(UUID.randomUUID(), "alice", Role.USER));
        when(healthConfigs.existsByApplicationIdAndEnabledTrue(APP_ID)).thenReturn(true);

        Application app = new Application();
        app.setServerIp(InetAddress.getByName("192.0.2.10"));
        app.setSshUsername("deploy");
        app.setSshPasswordEnc("encrypted");
        app.setSshHostKeyFingerprint("fingerprint");
        app.setStartScript("start");
        app.setStopScript("stop");
        when(applications.findById(APP_ID)).thenReturn(Optional.of(app));

        controller = new ApplicationController(cipher, mock(IdempotencyService.class), ssh,
            auditWriter, mock(LogPollingOrchestrator.class), mock(StatusPollingOrchestrator.class),
            List.of(), accessGuard, mock(UserRepository.class), applications,
            mock(ApplicationStatusStreakRepository.class),
            mock(ApplicationStatusTransitionRepository.class), healthConfigs,
            mock(ApplicationLogLineRepository.class), mock(AuditLogReader.class));
    }

    private void scriptExitsWith(int exitCode) throws Exception {
        when(ssh.runScriptWithTimeout(any(), anyString(), anyInt()))
            .thenReturn(new SshConnection.SshResult("", exitCode));
    }

    private void verifyOnlyFailureRow(String action, Map<String, Object> detail) {
        verify(auditWriter).write(eq(action), eq("alice"), eq("USER"), eq(APP_ID),
            isNull(), isNull(), eq(detail), eq("FAILURE"), eq(request));
        verifyNoMoreInteractions(auditWriter);
    }

    @Test
    void startScriptExitingNonZeroWritesFailureRow() throws Exception {
        scriptExitsWith(1);

        ResponseEntity<Map<String, Object>> response = controller.startApplication(APP_ID, null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        verifyOnlyFailureRow("START_APPLICATION", Map.of("reason", "SSH_COMMAND_FAILED"));
    }

    @Test
    void stopScriptExitingNonZeroWritesFailureRow() throws Exception {
        scriptExitsWith(1);

        ResponseEntity<Map<String, Object>> response = controller.stopApplication(APP_ID, null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        verifyOnlyFailureRow("STOP_APPLICATION", Map.of("reason", "SSH_COMMAND_FAILED"));
    }

    @Test
    void sshErrorOnStartWritesFailureRow() throws Exception {
        when(ssh.connect(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("connection refused"));

        controller.startApplication(APP_ID, null, request);

        verifyOnlyFailureRow("START_APPLICATION", Map.of("reason", "SSH_COMMAND_FAILED"));
    }

    @Test
    void brokenAuditWriteStillReturnsTheScriptFailure() throws Exception {
        scriptExitsWith(1);
        doThrow(new RuntimeException("database is locked")).when(auditWriter).write(
            anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), anyString(), any(HttpServletRequest.class));

        ResponseEntity<Map<String, Object>> response = controller.startApplication(APP_ID, null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).containsEntry("code", "SSH_COMMAND_FAILED");
    }
}
