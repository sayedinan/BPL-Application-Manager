package com.bpl.orderapp.admin.application;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.IdempotencyService;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.log.LogPollingOrchestrator;
import com.bpl.orderapp.admin.ssh.SshConnection;
import com.bpl.orderapp.admin.status.StatusConfirmationTimeoutException;
import com.bpl.orderapp.admin.status.StatusPollingOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per STATUS-REDESIGN.md: applications.status no longer exists.
 * Online/offline is derived live by {@link StatusPollingOrchestrator},
 * fed by each application's status_script. This controller never
 * writes a status value — it only triggers start/stop scripts and
 * waits for the poller to confirm the resulting state.
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApplicationController.class);

    // STATUS-REDESIGN.md §3 — different timeouts for start vs stop,
    // since shutdown is generally faster/more deterministic than boot.
    private static final long START_CONFIRMATION_TIMEOUT_MS = 30_000;
    private static final long STOP_CONFIRMATION_TIMEOUT_MS = 15_000;
    private static final long CONFIRMATION_POLL_INTERVAL_MS = 1_000;

    private final JdbcTemplate jdbc;
    private final SshCredentialCipher cipher;
    private final IdempotencyService idempotencyService;
    private final SshConnection sshConnection;
    private final AuditWriter auditWriter;
    private final LogPollingOrchestrator logPollingOrchestrator;
    private final StatusPollingOrchestrator statusPollingOrchestrator;

    public ApplicationController(JdbcTemplate jdbc, SshCredentialCipher cipher,
            IdempotencyService idempotencyService, SshConnection sshConnection,
            AuditWriter auditWriter, LogPollingOrchestrator logPollingOrchestrator,
            StatusPollingOrchestrator statusPollingOrchestrator) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.idempotencyService = idempotencyService;
        this.sshConnection = sshConnection;
        this.auditWriter = auditWriter;
        this.logPollingOrchestrator = logPollingOrchestrator;
        this.statusPollingOrchestrator = statusPollingOrchestrator;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new com.bpl.orderapp.admin.common.InvalidCredentialsException();
        }
        String username = auth.getPrincipal().toString();
        List<Map<String, Object>> userRows = jdbc.queryForList(
            "SELECT id, role FROM users WHERE username = ? AND deleted_at IS NULL", username);
        if (userRows.isEmpty()) {
            throw new com.bpl.orderapp.admin.common.InvalidCredentialsException();
        }
        Long userId = ((Number) userRows.get(0).get("id")).longValue();
        String role = (String) userRows.get(0).get("role");

        // online/streak_started_at come from application_status_streak
        // (STATUS-REDESIGN.md §4) via LEFT JOIN — a just-created
        // application may not have a streak row yet (its first status
        // check hasn't landed), in which case it reads as offline.
        List<Map<String, Object>> rows;
        if ("SYS_ADMIN".equals(role) || "ADMIN".equals(role)) {
            rows = jdbc.queryForList(
                "SELECT a.id, a.name, s.online, s.streak_started_at " +
                    "FROM applications a " +
                    "LEFT JOIN application_status_streak s ON s.application_id = a.id " +
                    "ORDER BY a.name");
        } else {
            rows = jdbc.queryForList(
                "SELECT a.id, a.name, s.online, s.streak_started_at FROM applications a " +
                    "JOIN user_application_assignments uaa ON uaa.application_id = a.id " +
                    "LEFT JOIN application_status_streak s ON s.application_id = a.id " +
                    "WHERE uaa.user_id = ? ORDER BY a.name",
                userId);
        }

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ((Number) row.get("id")).longValue());
            item.put("name", row.get("name"));
            boolean online = Boolean.TRUE.equals(row.get("online"));
            item.put("online", online);
            Object streakStartedAt = row.get("streak_started_at");
            // .toString() on a java.sql.Timestamp has no timezone marker
            // (e.g. "2026-09-14 09:30:00.0"), so the browser's `new Date(...)`
            // parses it as LOCAL time instead of UTC — inflating "running for"
            // by exactly the browser's UTC offset (6h for Dhaka). Format as a
            // proper ISO-8601 instant instead, same fix already applied in
            // AuditLogController. Only meaningful while online — matches the
            // old started_at semantics (null while stopped).
            item.put("startedAt", (online && streakStartedAt != null)
                ? ((java.sql.Timestamp) streakStartedAt).toInstant().toString()
                : null);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getApplication(@PathVariable Long id) {
        Map<String, Object> app = jdbc.queryForMap(
            "SELECT id, name, server_ip, ssh_username, ssh_host_key_fingerprint, start_script, stop_script, log_script, status_script, poll_interval_seconds FROM applications WHERE id = ?",
            id
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", ((Number) app.get("id")).longValue());
        body.put("name", app.get("name"));
        body.put("serverIp", app.get("server_ip").toString());
        body.put("sshUsername", app.get("ssh_username"));
        body.put("sshHostKeyFingerprint", app.get("ssh_host_key_fingerprint"));
        body.put("startScript", app.get("start_script"));
        body.put("stopScript", app.get("stop_script"));
        body.put("logScript", app.get("log_script"));
        body.put("statusScript", app.get("status_script"));
        body.put("pollIntervalSeconds", app.get("poll_interval_seconds"));
        body.put("online", statusPollingOrchestrator.isOnline(id));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, Object> req) {
        String serverIp = (String) req.get("serverIp");
        String sshUsername = (String) req.get("sshUsername");
        String sshPassword = (String) req.get("sshPassword");
        String sshHostKeyFingerprint = (String) req.get("sshHostKeyFingerprint");
        if (serverIp == null || !serverIp.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "VALIDATION_FAILED");
            err.put("message", "serverIp must be a valid IPv4 address");
            return ResponseEntity.status(400).body(err);
        }
        try {
            String encPass = cipher.encrypt(sshPassword);
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(serverIp, sshUsername, plainPass, sshHostKeyFingerprint);
            String presentedFingerprint = session.getHostKey().getFingerPrint(new com.jcraft.jsch.JSch());
            session.disconnect();
            Map<String, Object> ok = new HashMap<>();
            ok.put("status", "OK");
            ok.put("fingerprint", presentedFingerprint);
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            log.error("SSH connection test failed for server={}", serverIp, e);
            Map<String, Object> fail = new HashMap<>();
            fail.put("status", "SSH_CONNECTION_FAILED");
            fail.put("message", e.getMessage());
            return ResponseEntity.status(502).body(fail);
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<List<Map<String, Object>>> getLogs(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT line_number, content, captured_at FROM application_log_lines WHERE application_id = ? ORDER BY line_number ASC", id);
        return ResponseEntity.ok(rows);
    }

    @PostMapping
    public ResponseEntity<Void> createApplication(@RequestBody Map<String, Object> req, HttpServletRequest httpRequest) {
        String name = (String) req.get("name");
        String serverIp = (String) req.get("serverIp");
        if (serverIp == null || !serverIp.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "serverIp must be a valid IPv4 address");
        }
        String sshUsername = (String) req.get("sshUsername");
        String sshPassword = (String) req.get("sshPassword");
        String startScript = (String) req.get("startScript");
        String stopScript = (String) req.get("stopScript");
        String logScript = (String) req.get("logScript");
        String statusScript = (String) req.getOrDefault("statusScript", "");
        Object rawPoll = req.getOrDefault("pollIntervalSeconds", 5);
        int pollInterval = ((Number) rawPoll).intValue();
        String encPassword = cipher.encrypt(sshPassword);
        ShellCheckValidator validator = new ShellCheckValidator();
        try {
            validator.validate(startScript);
            validator.validate(stopScript);
            validator.validate(logScript);
            validator.validate(statusScript);
        } catch (Exception e) { /* fail-open per §12.3, unchanged */ }

        Long newId = jdbc.queryForObject(
            "INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, start_script, stop_script, log_script, status_script, poll_interval_seconds) " +
                "VALUES (?, ?::inet, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            name, serverIp, sshUsername, encPassword, (String) req.get("sshHostKeyFingerprint"),
            startScript, stopScript, logScript, statusScript, pollInterval);

        // Immediate first reading (STATUS-REDESIGN.md §4) rather than
        // sitting offline until the next scheduled tick.
        statusPollingOrchestrator.startPolling(newId);
        statusPollingOrchestrator.checkNow(newId);

        try {
            var actor = currentActor();
            auditWriter.write("CREATE_APPLICATION", actor.get("username"), actor.get("role"),
                newId, name, null, Map.of("serverIp", serverIp), "SUCCESS", httpRequest);
        } catch (Exception auditEx) {
            log.warn("Audit write failed for CREATE_APPLICATION (id={})", newId, auditEx);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> startApplication(@PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam Long userId, HttpServletRequest httpRequest) {
        try {
            idempotencyService.checkAndStore(idempotencyKey != null ? idempotencyKey : "", userId, id);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("IDEMPOTENCY_CONFLICT")) {
                Map<String, Object> conflictBody = new HashMap<>();
                conflictBody.put("code", "IDEMPOTENCY_CONFLICT");
                return ResponseEntity.status(409).body(conflictBody);
            }
            throw e;
        }

        Map<String, Object> app = jdbc.queryForMap(
            "SELECT server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, start_script FROM applications WHERE id = ?",
            id
        );
        String host = app.get("server_ip").toString();
        String user = (String) app.get("ssh_username");
        String encPass = (String) app.get("ssh_password_enc");
        String fingerprint = (String) app.get("ssh_host_key_fingerprint");
        String startScript = (String) app.get("start_script");

        statusPollingOrchestrator.pauseFor(id);
        boolean scriptSucceeded;
        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, startScript, 120000);
            session.disconnect();
            scriptSucceeded = result.exitCode == 0;
            if (!scriptSucceeded) {
                log.warn("Start script exited non-zero for application id={}", id);
            }
        } catch (Exception e) {
            log.error("Failed to run start script for application id={}", id, e);
            scriptSucceeded = false;
        } finally {
            statusPollingOrchestrator.resumeFor(id);
        }

        if (!scriptSucceeded) {
            Map<String, Object> body = new HashMap<>();
            body.put("code", "SSH_COMMAND_FAILED");
            return ResponseEntity.status(502).body(body);
        }

        boolean confirmed = waitForConfirmedStatus(id, true, START_CONFIRMATION_TIMEOUT_MS);
        if (!confirmed) {
            throw new StatusConfirmationTimeoutException(id, true);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("online", true);

        try {
            var actor = currentActor();
            auditWriter.write("START_APPLICATION", actor.get("username"), actor.get("role"),
                id, null, null, Map.of(), "SUCCESS", httpRequest);
        } catch (Exception auditEx) {
            log.warn("Audit write failed for START_APPLICATION (id={})", id, auditEx);
        }

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopApplication(@PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam Long userId, HttpServletRequest httpRequest) {
        idempotencyService.checkAndStore(idempotencyKey, userId, id);

        Map<String, Object> app = jdbc.queryForMap(
            "SELECT server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, stop_script FROM applications WHERE id = ?",
            id
        );
        String host = app.get("server_ip").toString();
        String user = (String) app.get("ssh_username");
        String encPass = (String) app.get("ssh_password_enc");
        String fingerprint = (String) app.get("ssh_host_key_fingerprint");
        String stopScript = (String) app.get("stop_script");

        statusPollingOrchestrator.pauseFor(id);
        boolean scriptSucceeded;
        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, stopScript, 120000);
            session.disconnect();
            scriptSucceeded = result.exitCode == 0;
            if (!scriptSucceeded) {
                log.warn("Stop script exited non-zero for application id={}", id);
            }
        } catch (Exception e) {
            log.error("Failed to run stop script for application id={}", id, e);
            scriptSucceeded = false;
        } finally {
            statusPollingOrchestrator.resumeFor(id);
        }

        if (!scriptSucceeded) {
            Map<String, Object> body = new HashMap<>();
            body.put("code", "SSH_COMMAND_FAILED");
            return ResponseEntity.status(502).body(body);
        }

        boolean confirmed = waitForConfirmedStatus(id, false, STOP_CONFIRMATION_TIMEOUT_MS);
        if (!confirmed) {
            throw new StatusConfirmationTimeoutException(id, false);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("online", false);

        try {
            var actor = currentActor();
            auditWriter.write("STOP_APPLICATION", actor.get("username"), actor.get("role"),
                id, null, null, Map.of(), "SUCCESS", httpRequest);
        } catch (Exception auditEx) {
            log.warn("Audit write failed for STOP_APPLICATION (id={})", id, auditEx);
        }

        return ResponseEntity.ok(body);
    }

    // STATUS-REDESIGN.md §3 — poll on the dedicated status connection
    // until the expected state is confirmed, or the timeout elapses.
    private boolean waitForConfirmedStatus(Long id, boolean expectedOnline, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean observed = statusPollingOrchestrator.checkNow(id);
        while (observed != expectedOnline && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(CONFIRMATION_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            observed = statusPollingOrchestrator.checkNow(id);
        }
        return observed == expectedOnline;
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> updateApplication(@PathVariable Long id, @RequestBody Map<String, Object> req, HttpServletRequest httpRequest) {
        if (statusPollingOrchestrator.isOnline(id)) {
            return ResponseEntity.status(403).build();
        }

        String name = (String) req.get("name");
        String serverIp = (String) req.get("serverIp");
        String sshUsername = (String) req.get("sshUsername");
        String startScript = (String) req.get("startScript");
        String stopScript = (String) req.get("stopScript");
        String logScript = (String) req.get("logScript");
        String statusScript = (String) req.get("statusScript");
        Object rawPoll = req.getOrDefault("pollIntervalSeconds", 5);
        int pollInterval = ((Number) rawPoll).intValue();

        String sshPassword = (String) req.get("sshPassword");
        if (sshPassword != null && !sshPassword.isBlank()) {
            String encPassword = cipher.encrypt(sshPassword);
            jdbc.update(
                "UPDATE applications SET name=?, server_ip=?::inet, ssh_username=?, ssh_password_enc=?, start_script=?, stop_script=?, log_script=?, status_script=?, poll_interval_seconds=?, updated_at=NOW() WHERE id=?",
                name, serverIp, sshUsername, encPassword, startScript, stopScript, logScript, statusScript, pollInterval, id);
        } else {
            jdbc.update(
                "UPDATE applications SET name=?, server_ip=?::inet, ssh_username=?, start_script=?, stop_script=?, log_script=?, status_script=?, poll_interval_seconds=?, updated_at=NOW() WHERE id=?",
                name, serverIp, sshUsername, startScript, stopScript, logScript, statusScript, pollInterval, id);
        }

        try {
            var actor = currentActor();
            auditWriter.write("UPDATE_APPLICATION", actor.get("username"), actor.get("role"),
                id, null, null, Map.of(), "SUCCESS", httpRequest);
        } catch (Exception auditEx) {
            log.warn("Audit write failed for UPDATE_APPLICATION (id={})", id, auditEx);
        }

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApplication(@PathVariable Long id, HttpServletRequest httpRequest) {
        if (statusPollingOrchestrator.isOnline(id)) {
            return ResponseEntity.status(409).build();
        }

        Map<String, Object> app = jdbc.queryForMap(
            "SELECT server_ip, ssh_username, ssh_host_key_fingerprint FROM applications WHERE id = ?", id);
        String host = app.get("server_ip").toString();
        String user = (String) app.get("ssh_username");
        String fingerprint = (String) app.get("ssh_host_key_fingerprint");

        statusPollingOrchestrator.stopPolling(id);
        logPollingOrchestrator.stopPolling(id);
        // Both cached connections must be closed — status polling and
        // log polling now live on independent sessions (SshConnection
        // "status" vs "default" purpose; see the ssh-connection-handling
        // skill update this implies).
        sshConnection.closeConnection(host, user, fingerprint, "status");
        sshConnection.closeConnection(host, user, fingerprint);

        jdbc.update("DELETE FROM applications WHERE id = ?", id);

        try {
            var actor = currentActor();
            auditWriter.write("DELETE_APPLICATION", actor.get("username"), actor.get("role"),
                id, null, null, Map.of(), "SUCCESS", httpRequest);
        } catch (Exception auditEx) {
            log.warn("Audit write failed for DELETE_APPLICATION (id={})", id, auditEx);
        }

        return ResponseEntity.noContent().build();
    }

    private Map<String, String> currentActor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        String role = jdbc.queryForObject(
            "SELECT role FROM users WHERE username = ? AND deleted_at IS NULL", String.class, username);
        return Map.of("username", username, "role", role);
    }
}
