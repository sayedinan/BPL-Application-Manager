package com.bpl.orderapp.admin.log;

import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.ssh.SshConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Runs log_script on an interval, only while an application is
 * currently online.
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
    private static final int LOG_SCRIPT_TIMEOUT_MS = 15000;

    private final JdbcTemplate jdbc;
    private final SshCredentialCipher cipher;
    private final SshConnection sshConnection;
    private final WebSocketBroadcast broadcast;
    private final ThreadPoolTaskScheduler scheduler;

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> activePollers = new ConcurrentHashMap<>();

    public LogPollingOrchestrator(JdbcTemplate jdbc, SshCredentialCipher cipher,
            SshConnection sshConnection, WebSocketBroadcast broadcast) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.sshConnection = sshConnection;
        this.broadcast = broadcast;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(4);
        this.scheduler.setThreadNamePrefix("log-poller-");
        this.scheduler.initialize();
    }

    public void startPolling(Long applicationId) {
        if (activePollers.containsKey(applicationId)) {
            return; // already running — avoid restarting on every redundant call
        }

        Integer intervalSeconds = jdbc.queryForObject(
            "SELECT poll_interval_seconds FROM applications WHERE id = ?", Integer.class, applicationId);
        int interval = intervalSeconds != null ? intervalSeconds : 5;

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> pollOnce(applicationId),
            Instant.now(),
            Duration.ofSeconds(interval));
        activePollers.put(applicationId, future);
        log.info("Started log poller for application id={} (interval={}s)", applicationId, interval);
    }

    public void stopPolling(Long applicationId) {
        ScheduledFuture<?> future = activePollers.remove(applicationId);
        if (future != null) {
            future.cancel(false);
            log.info("Stopped log poller for application id={}", applicationId);
        }
    }

    private void pollOnce(Long applicationId) {
        Map<String, Object> app;
        try {
            app = jdbc.queryForMap(
                "SELECT server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, log_script FROM applications WHERE id = ?",
                applicationId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            stopPolling(applicationId);
            return;
        }

        String host = app.get("server_ip").toString();
        String user = (String) app.get("ssh_username");
        String encPass = (String) app.get("ssh_password_enc");
        String fingerprint = (String) app.get("ssh_host_key_fingerprint");
        String logScript = (String) app.get("log_script");

        String rawOutput;
        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, logScript, LOG_SCRIPT_TIMEOUT_MS);
            rawOutput = result.stdout;
        } catch (Exception e) {
            log.warn("Log poll failed for application id={}: {}", applicationId, e.getMessage());
            sshConnection.invalidateCache(host, user, fingerprint);
            appendAndBroadcast(applicationId, "[ERROR] Log poll failed: " + e.getMessage());
            return;
        }

        List<String> newLines = extractNewLines(applicationId, rawOutput);
        for (String line : newLines) {
            appendAndBroadcast(applicationId, line);
        }
    }

    private List<String> extractNewLines(Long applicationId, String rawOutput) {
        String[] batch = rawOutput.split("\n");
        int end = batch.length;
        while (end > 0 && batch[end - 1].isBlank()) end--;

        List<String> lastContentRows = jdbc.queryForList(
            "SELECT content FROM application_log_lines WHERE application_id = ? ORDER BY line_number DESC LIMIT 1",
            String.class, applicationId);

        if (lastContentRows.isEmpty()) {
            return Arrays.asList(Arrays.copyOf(batch, end));
        }

        String lastContent = lastContentRows.get(0);
        for (int i = end - 1; i >= 0; i--) {
            if (batch[i].equals(lastContent)) {
                return Arrays.asList(Arrays.copyOfRange(batch, i + 1, end));
            }
        }
        return Arrays.asList(Arrays.copyOf(batch, end));
    }

    private void appendAndBroadcast(Long applicationId, String content) {
        Long lastLineNumber = jdbc.queryForObject(
            "SELECT COALESCE(MAX(line_number), 0) FROM application_log_lines WHERE application_id = ?",
            Long.class, applicationId);
        long nextLineNumber = lastLineNumber + 1;
        Instant now = Instant.now();
        jdbc.update(
            "INSERT INTO application_log_lines (application_id, line_number, content, captured_at) VALUES (?, ?, ?, ?)",
            applicationId, nextLineNumber, content, java.sql.Timestamp.from(now));
        jdbc.update(
            "DELETE FROM application_log_lines WHERE application_id = ? AND line_number <= ?",
            applicationId, nextLineNumber - MAX_RETAINED_LINES);
        broadcast.broadcast(applicationId, content, nextLineNumber, now.toString());
    }
}
