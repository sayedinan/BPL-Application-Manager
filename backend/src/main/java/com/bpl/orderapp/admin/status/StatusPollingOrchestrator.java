package com.bpl.orderapp.admin.status;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.log.LogPollingOrchestrator;
import com.bpl.orderapp.admin.log.WebSocketBroadcast;
import com.bpl.orderapp.admin.ssh.SshConnection;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Always-on, two-way status reconciliation poller.
 *
 * <p>Per STATUS-REDESIGN.md: online/offline is no longer a column
 * applications write on start/stop — it is derived live by running
 * each application's Sys.Admin-authored {@code status_script} over
 * SSH, on its own cached connection (purpose {@code "status"},
 * independent of the log poller's connection — see SshConnection),
 * on a hardcoded interval.
 *
 * <p>Unlike the log poller, this runs continuously for every
 * application regardless of last-known state — two-way reconciliation
 * requires it, since it must catch both "thought online, actually
 * dead" and "thought offline, actually running" (started manually
 * outside the app).
 *
 * <p>Convention: {@code status_script} exit code 0 = online; any
 * non-zero exit, or an SSH/connection failure, = offline. Mirrors the
 * exit codes a Sys.Admin would already get from
 * {@code systemctl is-active}, {@code pgrep -f ...}, or
 * {@code curl -sf <health-url>}.
 */
@Component
public class StatusPollingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StatusPollingOrchestrator.class);

    // Hardcoded per STATUS-REDESIGN.md §2 — not user-configurable,
    // independent of the log poller's per-application
    // poll_interval_seconds.
    private static final int STATUS_POLL_INTERVAL_SECONDS = 3;
    private static final int STATUS_SCRIPT_TIMEOUT_MS = 8000;

    private final JdbcTemplate jdbc;
    private final SshCredentialCipher cipher;
    private final SshConnection sshConnection;
    private final WebSocketBroadcast broadcast;
    private final LogPollingOrchestrator logPollingOrchestrator;
    private final ThreadPoolTaskScheduler scheduler;
    private final AuditWriter auditWriter;

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> activePollers = new ConcurrentHashMap<>();

    // Set while a start/stop script is executing for an application, so
    // this poller's tick skips it rather than racing the in-flight
    // action. Cleared unconditionally when the action finishes (success
    // or failure). Not persisted — a mid-flight action wouldn't survive
    // a backend restart either, so "not paused" is the right default
    // after one.
    private final Set<Long> actionInProgress = ConcurrentHashMap.newKeySet();

    public StatusPollingOrchestrator(JdbcTemplate jdbc, SshCredentialCipher cipher,
            SshConnection sshConnection, WebSocketBroadcast broadcast,
            LogPollingOrchestrator logPollingOrchestrator, AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.sshConnection = sshConnection;
        this.broadcast = broadcast;
        this.logPollingOrchestrator = logPollingOrchestrator;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(4);
        this.scheduler.setThreadNamePrefix("status-poller-");
        this.scheduler.initialize();
    }

    // Every application gets a poller, always — the "always-on" half of
    // two-way reconciliation (STATUS-REDESIGN.md §2), unlike the log
    // poller which only runs while online.
    @PostConstruct
    public void startPollersForAllApplications() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM applications", Long.class);
        for (Long id : ids) {
            ensureStreakRow(id);
            startPolling(id);
        }
    }

    public void startPolling(Long applicationId) {
        stopPolling(applicationId);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> pollOnce(applicationId),
            Instant.now(),
            Duration.ofSeconds(STATUS_POLL_INTERVAL_SECONDS));
        activePollers.put(applicationId, future);
        log.info("Started status poller for application id={} (interval={}s)",
            applicationId, STATUS_POLL_INTERVAL_SECONDS);
    }

    public void stopPolling(Long applicationId) {
        ScheduledFuture<?> future = activePollers.remove(applicationId);
        if (future != null) {
            future.cancel(false);
            log.info("Stopped status poller for application id={}", applicationId);
        }
    }

    // Called by ApplicationController immediately before running a
    // start/stop script, so this poller's next tick(s) skip this
    // application until the action completes.
    public void pauseFor(Long applicationId) {
        actionInProgress.add(applicationId);
    }

    public void resumeFor(Long applicationId) {
        actionInProgress.remove(applicationId);
    }

    // Who clicked Start/Stop in this web app, so the resulting online/offline
    // flip can be audited with their name. Set before the script runs, cleared
    // by ApplicationController once confirmation finishes (success, failure or
    // timeout) - deliberately outlives pauseFor/resumeFor, because the flip is
    // detected AFTER the script ends.
    private record PendingAction(String username, String role, boolean expectedOnline) {}
    private final ConcurrentHashMap<Long, PendingAction> pendingActions = new ConcurrentHashMap<>();

    public void expectAction(Long applicationId, String username, String role, boolean expectedOnline) {
        pendingActions.put(applicationId, new PendingAction(username, role, expectedOnline));
    }

    public void clearExpectedAction(Long applicationId) {
        pendingActions.remove(applicationId);
    }

    // One audit row per detected flip. If a web-app action for this
    // application is pending AND its expected state matches what was observed,
    // the flip is attributed to that user. Anything else (terminal, crash,
    // docker restart, reboot) is recorded as system/EXTERNAL.
    private void writeTransitionAudit(Long applicationId, boolean observedOnline) {
        try {
            String name = jdbc.queryForObject(
                "SELECT name FROM applications WHERE id = ?", String.class, applicationId);
            PendingAction pending = pendingActions.get(applicationId);
            boolean viaWebApp = pending != null && pending.expectedOnline() == observedOnline;
            java.util.Map<String, Object> detail = new java.util.HashMap<>();
            String actor;
            String role;
            if (viaWebApp) {
                actor = pending.username();
                role = pending.role();
                detail.put("source", "WEB_APP");
                detail.put("triggeredBy", actor);
            } else {
                actor = "system";
                role = "SYSTEM";
                detail.put("source", "EXTERNAL");
            }
            auditWriter.write(observedOnline ? "APPLICATION_ONLINE" : "APPLICATION_OFFLINE",
                actor, role, applicationId, name, null, detail, "SUCCESS");
        } catch (Exception e) {
            log.warn("Audit write failed for status transition (application id={})", applicationId, e);
        }
    }

    // Synchronous, on-demand check. Used by ApplicationController's
    // post-action poll-until-confirmed loop (called repeatedly with a
    // short sleep between calls, rather than waiting for the next
    // scheduled tick) and immediately after application creation to get
    // a real first reading instead of defaulting to offline forever.
    public boolean checkNow(Long applicationId) {
        return runCheck(applicationId);
    }

    // Cheap lookup — no SSH, just the streak pointer's last-known value.
    // This is what GET /applications reads for the dashboard badge.
    public boolean isOnline(Long applicationId) {
        List<Boolean> rows = jdbc.queryForList(
            "SELECT online FROM application_status_streak WHERE application_id = ?",
            Boolean.class, applicationId);
        return !rows.isEmpty() && rows.get(0);
    }

    private void pollOnce(Long applicationId) {
        if (actionInProgress.contains(applicationId)) {
            return;
        }
        runCheck(applicationId);
    }

    // Runs status_script, determines online/offline, and reconciles
    // against the streak pointer — recording a transition + broadcasting
    // only when the value actually changed. Returns the freshly-observed
    // value either way.
    private boolean runCheck(Long applicationId) {
        Map<String, Object> app;
        try {
            app = jdbc.queryForMap(
                "SELECT server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, status_script FROM applications WHERE id = ?",
                applicationId);
        } catch (EmptyResultDataAccessException e) {
            stopPolling(applicationId);
            return false;
        }

        String host = app.get("server_ip").toString();
        String user = (String) app.get("ssh_username");
        String encPass = (String) app.get("ssh_password_enc");
        String fingerprint = (String) app.get("ssh_host_key_fingerprint");
        String statusScript = (String) app.get("status_script");

        boolean online;
        if (statusScript == null || statusScript.isBlank()) {
            // No status script configured yet — treat as offline rather
            // than throwing, so an application mid-setup doesn't crash
            // the poller loop.
            online = false;
        } else {
            try {
                String plainPass = cipher.decrypt(encPass);
                com.jcraft.jsch.Session session =
                    sshConnection.connect(host, user, plainPass, fingerprint, "status");
                SshConnection.SshResult result =
                    sshConnection.runScriptWithTimeout(session, statusScript, STATUS_SCRIPT_TIMEOUT_MS);
                online = result.exitCode == 0;
            } catch (Exception e) {
                log.warn("Status check failed for application id={}: {}", applicationId, e.getMessage());
                sshConnection.invalidateCache(host, user, fingerprint, "status");
                online = false;
            }
        }

        reconcile(applicationId, online);
        return online;
    }

    private void ensureStreakRow(Long applicationId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM application_status_streak WHERE application_id = ?",
            Integer.class, applicationId);
        if (count != null && count == 0) {
            jdbc.update(
                "INSERT INTO application_status_streak (application_id, online, streak_started_at) VALUES (?, false, NOW())",
                applicationId);
        }
    }

    private void reconcile(Long applicationId, boolean observedOnline) {
        List<Boolean> rows = jdbc.queryForList(
            "SELECT online FROM application_status_streak WHERE application_id = ?",
            Boolean.class, applicationId);
        boolean hadStreakRow = !rows.isEmpty();
        boolean previousOnline = hadStreakRow && rows.get(0);

        if (hadStreakRow && previousOnline == observedOnline) {
            return; // no change — nothing to record or broadcast
        }

        Instant now = Instant.now();
        jdbc.update(
            "INSERT INTO application_status_transitions (application_id, online, transitioned_at) VALUES (?, ?, ?)",
            applicationId, observedOnline, Timestamp.from(now));

        if (hadStreakRow) {
            jdbc.update(
                "UPDATE application_status_streak SET online = ?, streak_started_at = ? WHERE application_id = ?",
                observedOnline, Timestamp.from(now), applicationId);
        } else {
            jdbc.update(
                "INSERT INTO application_status_streak (application_id, online, streak_started_at) VALUES (?, ?, ?)",
                applicationId, observedOnline, Timestamp.from(now));
        }

        broadcast.broadcastStatus(applicationId, observedOnline, now.toString());
        log.info("Application id={} transitioned to {}", applicationId, observedOnline ? "ONLINE" : "OFFLINE");

        // First-ever reading (no streak row yet) is an initial state, not a change.
        if (hadStreakRow) {
            writeTransitionAudit(applicationId, observedOnline);
        }

        // The log poller's on/off switch is now driven by this derived
        // signal, not a stored status column (STATUS-REDESIGN.md §5).
        // This also naturally handles boot-time resume: a just-started
        // poller's first check on an already-online application reports
        // online != previousOnline(false, the ensureStreakRow default),
        // so this fires without any special "resume on startup" logic —
        // same "no special-casing needed" pattern already used by the
        // log dedup algorithm.
        if (observedOnline) {
            logPollingOrchestrator.startPolling(applicationId);
        } else {
            logPollingOrchestrator.stopPolling(applicationId);
        }
    }
}
