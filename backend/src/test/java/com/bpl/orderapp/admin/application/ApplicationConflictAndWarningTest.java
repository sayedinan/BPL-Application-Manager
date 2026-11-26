package com.bpl.orderapp.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ApplicationConflictAndWarningTest {

    private final SshConnection ssh = mock(SshConnection.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private ApplicationController controller;

    @BeforeEach
    void setUp() throws Exception {
        AccessGuard guard = mock(AccessGuard.class);
        when(guard.currentCaller()).thenReturn(new AccessGuard.Caller(UUID.randomUUID(), "alice", Role.SYS_ADMIN));
        ApplicationHealthConfigRepository health = mock(ApplicationHealthConfigRepository.class);
        when(health.existsByApplicationIdAndEnabledTrue(any())).thenReturn(true);
        Application app = new Application();
        app.setServerIp(InetAddress.getByName("192.0.2.1"));
        when(applications.findById(any())).thenReturn(Optional.of(app));
        Application saved = mock(Application.class);
        when(saved.getId()).thenReturn(1L);
        when(applications.save(any())).thenReturn(saved);
        controller = new ApplicationController(mock(SshCredentialCipher.class), mock(IdempotencyService.class),
            ssh, mock(AuditWriter.class), mock(LogPollingOrchestrator.class), mock(StatusPollingOrchestrator.class),
            List.of(), guard, mock(UserRepository.class), applications,
            mock(ApplicationStatusStreakRepository.class), mock(ApplicationStatusTransitionRepository.class),
            health, mock(ApplicationLogLineRepository.class), mock(AuditLogReader.class));
    }

    @Test
    void whileAStartIsRunningTheSameApplicationCannotBeStoppedEditedOrDeleted() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ssh.connect(any(), any(), any(), any())).thenAnswer(i -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            throw new IllegalStateException("unreachable");
        });
        CompletableFuture<?> first = CompletableFuture.supplyAsync(
            () -> controller.startApplication(1L, null, request));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> controller.stopApplication(1L, null, request))
                .isInstanceOf(ApplicationBusyException.class);
            assertThatThrownBy(() -> controller.startApplication(1L, null, request))
                .isInstanceOf(ApplicationBusyException.class);
            assertThatThrownBy(() -> controller.updateApplication(1L, Map.of(), request))
                .isInstanceOf(ApplicationBusyException.class);
            assertThatThrownBy(() -> controller.deleteApplication(1L, request))
                .isInstanceOf(ApplicationBusyException.class);
        } finally {
            release.countDown();
        }
        first.get(5, TimeUnit.SECONDS);

        // The lock is released when the action ends, including a failed one.
        controller.deleteApplication(1L, request);
    }

    private Object create() {
        return controller.createApplication(new java.util.HashMap<>(Map.of(
            "name", "a1", "serverIp", "192.0.2.1", "sshUsername", "u", "sshPassword", "p",
            "sshHostKeyFingerprint", "SHA256:x", "startScript", "true", "stopScript", "true",
            "logScript", "true")), request);
    }

    private void shellCheck(boolean available) throws Exception {
        ShellCheckValidator validator = mock(ShellCheckValidator.class);
        if (!available) {
            when(validator.validate(any())).thenThrow(
                new ShellCheckValidator.ShellCheckUnavailableException("missing", null));
        }
        ReflectionTestUtils.setField(controller, "shellCheck", validator);
    }

    @Test
    void savingWithoutScriptValidationSaysSo() throws Exception {
        shellCheck(false);

        assertThat(create().toString()).contains("Script validation unavailable");
    }

    @Test
    void savingWithScriptValidationHasNoWarning() throws Exception {
        shellCheck(true);

        assertThat(create().toString()).doesNotContain("validation unavailable");
    }
}
