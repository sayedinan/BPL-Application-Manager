package com.bpl.orderapp.admin.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bpl.orderapp.admin.audit.AuditLogReader;
import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.IdempotencyService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

/** Saved SSH credentials are only ever sent to a server whose host key is pinned. */
class SshHostKeyRequiredTest {

    private final ApplicationRepository applications = mock(ApplicationRepository.class);

    private final ApplicationController controller = new ApplicationController(
        mock(SshCredentialCipher.class), mock(IdempotencyService.class), mock(SshConnection.class),
        mock(AuditWriter.class), mock(LogPollingOrchestrator.class), mock(StatusPollingOrchestrator.class),
        List.of(), mock(AccessGuard.class), mock(UserRepository.class), applications,
        mock(ApplicationStatusStreakRepository.class), mock(ApplicationStatusTransitionRepository.class),
        mock(ApplicationHealthConfigRepository.class), mock(ApplicationLogLineRepository.class),
        mock(AuditLogReader.class));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void connectingWithoutAFingerprintIsRefusedBeforeAnyNetworkTraffic(String fingerprint) {
        // 192.0.2.0/24 is reserved for documentation: nothing answers there.
        assertThatThrownBy(() -> new SshConnection().connect("192.0.2.1", "deploy", "secret", fingerprint))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("fingerprint");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void anApplicationCannotBeCreatedWithoutAFingerprint(String fingerprint) {
        Map<String, Object> body = new HashMap<>(Map.of(
            "name", "a1", "serverIp", "192.0.2.1", "sshUsername", "deploy", "sshPassword", "secret",
            "startScript", "s", "stopScript", "s", "logScript", "l"));
        body.put("sshHostKeyFingerprint", fingerprint);

        assertThatThrownBy(() -> controller.createApplication(body, mock(HttpServletRequest.class)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("fingerprint");

        verify(applications, never()).save(any());
    }
}
