package com.bpl.orderapp.admin.log;

import com.bpl.orderapp.admin.application.Application;
import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.ssh.SshConnection;
import com.bpl.orderapp.admin.websocket.WebSocketSubscriptionRepository;
import com.bpl.orderapp.admin.status.ApplicationStatusStreakRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Runs log_script on an interval, only while the application is
 * online AND at least one viewer has its log topic open.
 *
 * <p>Per STATUS-REDESIGN.md §5, "online" is no longer a stored column
 * this class can query at boot — start/stop of this poller is now
 * driven entirely by {@link com.bpl.orderapp.admin.status.StatusPollingOrchestrator},
 * which calls {@link #startPolling}/{@link #stopPolling} whenever it
 * detects an online/offline transition (including the very first
 * check after a backend restart, which naturally resumes polling for
 * anything already online — no separate boot-time query needed here
 * anymore).
 */
@Component
public class LogPollingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LogPollingOrchestrator.class);
    private static final int MAX_RETAINED_LINES = 500;
    private static final int LOG_POLL_INTERVAL_SECONDS = 5;
    private static final int LOG_SCRIPT_TIMEOUT_MS = 15000;
    private static final String FAILURE_PREFIX = "[ERROR] Log poll failed: ";

    private final ConcurrentHashMap<Long, String> lastFailure = new ConcurrentHashMap<>();

    private final SshCredentialCipher cipher;
    private final SshConnection sshConnection;
    private final WebSocketBroadcast broadcast;
    private final ThreadPoolTaskScheduler scheduler;

    @jakarta.annotation.PreDestroy
    public void shutdownScheduler() {
        scheduler.shutdown();
    }
    private final ApplicationRepository applicationRepository;
    private final ApplicationStatusStreakRepository streakRepository;
    private final ApplicationLogLineRepository logLineRepository;
    private final WebSocketSubscriptionRepository subscriptionRepository;

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> activePollers = new ConcurrentHashMap<>();

    public LogPollingOrchestrator(SshCredentialCipher cipher,
            SshConnection sshConnection, WebSocketBroadcast broadcast, ApplicationRepository applicationRepository,
            ApplicationStatusStreakRepository streakRepository,
            ApplicationLogLineRepository logLineRepository,
            WebSocketSubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.logLineRepository = logLineRepository;
        this.streakRepository = streakRepository;
        this.cipher = cipher;
        this.sshConnection = sshConnection;
        this.broadcast = broadcast;
        this.applicationRepository = applicationRepository;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(4);
        this.scheduler.setThreadNamePrefix("log-poller-");
        this.scheduler.initialize();
    }

    public void startPolling(Long applicationId) {
        if (activePollers.containsKey(applicationId)) {
            return; // already running — avoid restarting on every redundant call
        }

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> pollOnce(applicationId),
            Instant.now(),
            Duration.ofSeconds(LOG_POLL_INTERVAL_SECONDS));
        activePollers.put(applicationId, future);
        log.info("Started log poller for application id={} (interval={}s)", applicationId, LOG_POLL_INTERVAL_SECONDS);
    }

    public void stopPolling(Long applicationId) {
        ScheduledFuture<?> future = activePollers.remove(applicationId);
        if (future != null) {
            future.cancel(false);
            log.info("Stopped log poller for application id={}", applicationId);
        }
    }

    public int viewerCount(Long applicationId) {
        return (int) subscriptionRepository.countByTopicKey(String.valueOf(applicationId));
    }

    private boolean isOnline(Long applicationId) {
        return streakRepository.isOnline(applicationId);
    }

    @EventListener
    public void onViewerEvent(LogViewerEvent event) {
        Long id = event.applicationId();
        if (event.joined()) {
            if (isOnline(id)) startPolling(id);
        } else if (viewerCount(id) == 0) {
            stopPolling(id);
        }
    }

    /** Called by the status poller when an application flips online: poll only if someone is watching. */
    public void onAppOnline(Long applicationId) {
        if (viewerCount(applicationId) > 0) startPolling(applicationId);
    }

    private void pollOnce(Long applicationId) {
        if (viewerCount(applicationId) == 0) {
            stopPolling(applicationId);
            return;
        }
        java.util.Optional<Application> found = applicationRepository.findById(applicationId);
        if (found.isEmpty()) {
            stopPolling(applicationId);
            return;
        }
        Application app = found.get();
        // getHostAddress(), not toString(): toString() would give "/10.0.0.5".
        String host = app.getServerIp().getHostAddress();
        String user = app.getSshUsername();
        String encPass = app.getSshPasswordEnc();
        String fingerprint = app.getSshHostKeyFingerprint();
        String logScript = app.getLogScript();

        String rawOutput;
        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, logScript, LOG_SCRIPT_TIMEOUT_MS);
            rawOutput = result.stdout;
        } catch (Exception e) {
            log.warn("Log poll failed for application id={}: {}", applicationId, e.getMessage());
            sshConnection.invalidateCache(host, user, fingerprint);
            // Recorded once per failure: the same one every few seconds would push the
            // real log out of the retained lines.
            String failure = FAILURE_PREFIX + e.getMessage();
            if (!failure.equals(lastFailure.put(applicationId, failure))) {
                appendAndBroadcast(applicationId, failure);
            }
            return;
        }

        lastFailure.remove(applicationId);
        List<String> newLines = extractNewLines(applicationId, rawOutput);
        for (String line : newLines) {
            appendAndBroadcast(applicationId, line);
        }
    }

    private List<String> extractNewLines(Long applicationId, String rawOutput) {
        String[] batch = rawOutput.split("\n");
        int end = batch.length;
        while (end > 0 && batch[end - 1].isBlank()) end--;

        // The poller's own error lines are not part of the remote log.
        List<String> stored = logLineRepository.findByApplicationIdOrderByLineNumberAsc(applicationId).stream()
            .map(ApplicationLogLine::getContent)
            .filter(content -> !content.startsWith(FAILURE_PREFIX))
            .toList();
        return LogDedup.newLines(Arrays.asList(Arrays.copyOf(batch, end)), stored);
    }

    private void appendAndBroadcast(Long applicationId, String content) {
        long nextLineNumber = logLineRepository.findFirstByApplicationIdOrderByLineNumberDesc(applicationId)
            .map(last -> last.getLineNumber() + 1)
            .orElse(1L);
        Instant now = Instant.now();
        logLineRepository.save(new ApplicationLogLine(applicationId, nextLineNumber, content, now));
        logLineRepository.deleteUpToLine(applicationId, nextLineNumber - MAX_RETAINED_LINES);
        broadcast.broadcast(applicationId, content, nextLineNumber, now.toString());
    }
}
