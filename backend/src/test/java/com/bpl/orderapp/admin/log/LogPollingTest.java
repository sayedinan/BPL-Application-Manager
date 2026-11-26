package com.bpl.orderapp.admin.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bpl.orderapp.admin.application.Application;
import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.ssh.SshConnection;
import com.bpl.orderapp.admin.status.ApplicationStatusStreakRepository;
import com.bpl.orderapp.admin.websocket.WebSocketSubscriptionRepository;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** What ends up in the stored log when polls repeat, fail and recover. */
class LogPollingTest {

    private final List<ApplicationLogLine> stored = new ArrayList<>();
    private final SshConnection ssh = mock(SshConnection.class);
    private LogPollingOrchestrator poller;

    @BeforeEach
    void setUp() throws Exception {
        ApplicationLogLineRepository lines = mock(ApplicationLogLineRepository.class);
        when(lines.save(any())).thenAnswer(i -> {
            stored.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(lines.findByApplicationIdOrderByLineNumberAsc(1L)).thenAnswer(i -> List.copyOf(stored));
        when(lines.findFirstByApplicationIdOrderByLineNumberDesc(1L)).thenAnswer(
            i -> stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        ApplicationRepository apps = mock(ApplicationRepository.class);
        Application app = new Application();
        app.setServerIp(InetAddress.getByName("192.0.2.1"));
        when(apps.findById(1L)).thenReturn(Optional.of(app));
        WebSocketSubscriptionRepository viewers = mock(WebSocketSubscriptionRepository.class);
        when(viewers.countByTopicKey("1")).thenReturn(1L);
        SshCredentialCipher cipher = mock(SshCredentialCipher.class);
        poller = new LogPollingOrchestrator(cipher, ssh, mock(WebSocketBroadcast.class), apps,
            mock(ApplicationStatusStreakRepository.class), lines, viewers);
    }

    @AfterEach
    void shutDown() {
        poller.shutdownScheduler();
    }

    private void poll(String output) throws Exception {
        org.mockito.Mockito.doReturn(new SshConnection.SshResult(output, 0))
            .when(ssh).runScriptWithTimeout(any(), any(), anyInt());
        ReflectionTestUtils.invokeMethod(poller, "pollOnce", 1L);
    }

    private void failingPoll(String reason) throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException(reason))
            .when(ssh).runScriptWithTimeout(any(), any(), anyInt());
        ReflectionTestUtils.invokeMethod(poller, "pollOnce", 1L);
    }

    private List<String> contents() {
        return stored.stream().map(ApplicationLogLine::getContent).toList();
    }

    @Test
    void recoveringFromAFailureDoesNotRepeatOldLines() throws Exception {
        poll("a\nb\n");
        failingPoll("timeout");
        poll("a\nb\nc\n");

        assertThat(contents()).containsExactly("a", "b", "[ERROR] Log poll failed: timeout", "c");
    }

    @Test
    void theSameFailureRepeatingIsRecordedOnceUntilSomethingChanges() throws Exception {
        poll("a\n");
        for (int i = 0; i < 20; i++) failingPoll("timeout");
        failingPoll("refused");
        poll("a\nb\n");
        failingPoll("refused");

        assertThat(contents()).containsExactly("a", "[ERROR] Log poll failed: timeout",
            "[ERROR] Log poll failed: refused", "b", "[ERROR] Log poll failed: refused");
    }

    @Test
    void aNewLineThatRepeatsTheLastOneDoesNotHideTheLinesBeforeIt() throws Exception {
        poll("start\nheartbeat\n");
        poll("start\nheartbeat\nwork done\nheartbeat\n");

        assertThat(contents()).containsExactly("start", "heartbeat", "work done", "heartbeat");
    }
}
