package com.bpl.orderapp.admin.application;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import com.bpl.orderapp.admin.common.IdempotencyService;
import com.bpl.orderapp.admin.ssh.SshConnection;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApplicationController.class);
    private final JdbcTemplate jdbc;
    private final SshCredentialCipher cipher;
    private final IdempotencyService idempotencyService;
    private final SshConnection sshConnection;
    public ApplicationController(JdbcTemplate jdbc, SshCredentialCipher cipher, IdempotencyService idempotencyService, SshConnection sshConnection) {
        this.jdbc = jdbc; this.cipher = cipher; this.idempotencyService = idempotencyService;
        this.sshConnection = sshConnection;
    }
    @GetMapping
    public ResponseEntity<java.util.List<Map<String, Object>>> list() {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new com.bpl.orderapp.admin.common.InvalidCredentialsException();
        }
        String username = auth.getPrincipal().toString();
        java.util.List<Map<String, Object>> userRows = jdbc.queryForList(
            "SELECT id, role FROM users WHERE username = ? AND deleted_at IS NULL", username);
        if (userRows.isEmpty()) {
            throw new com.bpl.orderapp.admin.common.InvalidCredentialsException();
        }
        Long userId = ((Number) userRows.get(0).get("id")).longValue();
        String role = (String) userRows.get(0).get("role");
        java.util.List<Map<String, Object>> rows;
        if ("SYS_ADMIN".equals(role)) {
            rows = jdbc.queryForList(
                "SELECT id, name, status, started_at FROM applications ORDER BY name");
        } else {
            rows = jdbc.queryForList(
                "SELECT a.id, a.name, a.status, a.started_at FROM applications a " +
                    "JOIN user_application_assignments uaa ON uaa.application_id = a.id " +
                    "WHERE uaa.user_id = ? ORDER BY a.name",
                userId);
        }
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", ((Number) row.get("id")).longValue());
            item.put("name", row.get("name"));
            item.put("status", row.get("status"));
            Object startedAt = row.get("started_at");
            item.put("startedAt", startedAt == null ? null : startedAt.toString());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }
    @GetMapping("/{id}")
    public ResponseEntity<Map<String,Object>> getApplication(@PathVariable Long id) {
        Map<String,Object> app = jdbc.queryForMap(
            "SELECT id, name, server_ip, ssh_username, ssh_host_key_fingerprint, start_script, stop_script, log_script, poll_interval_seconds, status FROM applications WHERE id = ?",
            id
        );
        Map<String,Object> body = new java.util.LinkedHashMap<>();
        body.put("id", ((Number) app.get("id")).longValue());
        body.put("name", app.get("name"));
        body.put("serverIp", app.get("server_ip").toString());
        body.put("sshUsername", app.get("ssh_username"));
        body.put("sshHostKeyFingerprint", app.get("ssh_host_key_fingerprint"));
        body.put("startScript", app.get("start_script"));
        body.put("stopScript", app.get("stop_script"));
        body.put("logScript", app.get("log_script"));
        body.put("pollIntervalSeconds", app.get("poll_interval_seconds"));
        body.put("status", app.get("status"));
        return ResponseEntity.ok(body);
    }
    @PostMapping
    public ResponseEntity<Void> createApplication(@RequestBody Map<String, Object> req) {
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
        Object rawPoll = req.getOrDefault("pollIntervalSeconds", 5);
        int pollInterval = ((Number) rawPoll).intValue();
        String encPassword = cipher.encrypt(sshPassword);
        ShellCheckValidator validator = new ShellCheckValidator();
        try { validator.validate(startScript); validator.validate(stopScript); validator.validate(logScript); } catch (Exception e) { }
        jdbc.update("INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, start_script, stop_script, log_script, poll_interval_seconds, status) VALUES (?, ?::inet, ?, ?, ?, ?, ?, ?, ?, 'STOPPED')", name, serverIp, sshUsername, encPassword, (String)req.get("sshHostKeyFingerprint"), startScript, stopScript, logScript, pollInterval);
        return ResponseEntity.ok().build();
    }
    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String,Object>> startApplication(@PathVariable Long id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @RequestParam Long userId) {
        try {
            idempotencyService.checkAndStore(idempotencyKey != null ? idempotencyKey : "", userId, id);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("IDEMPOTENCY_CONFLICT")) {
                Map<String,Object> conflictBody = new java.util.HashMap<>();
                conflictBody.put("status", "CONFLICT");
                return ResponseEntity.status(409).body(conflictBody);
            }
            throw e;
        }
        jdbc.update("UPDATE applications SET status='STARTING', started_at=NULL WHERE id=?", id);

        Map<String, Object> app = jdbc.queryForMap(
            "SELECT server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, start_script FROM applications WHERE id = ?",
            id
        );
        String host = app.get("server_ip").toString();
        String user = (String) app.get("ssh_username");
        String encPass = (String) app.get("ssh_password_enc");
        String fingerprint = (String) app.get("ssh_host_key_fingerprint");
        String startScript = (String) app.get("start_script");

        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, startScript, 120000);
            session.disconnect();
            if (result.exitCode == 0) {
                jdbc.update("UPDATE applications SET status='RUNNING', started_at=NOW() WHERE id=?", id);
            } else {
                jdbc.update("UPDATE applications SET status='ERROR' WHERE id=?", id);
            }
        } catch (Exception e) {
            log.error("Failed to start/stop application id={}", id, e);
            jdbc.update("UPDATE applications SET status='ERROR' WHERE id=?", id);
        }

        String finalStatus = jdbc.queryForObject("SELECT status FROM applications WHERE id=?", String.class, id);
        Map<String,Object> body = new java.util.HashMap<>();
        body.put("status", finalStatus);
        if ("ERROR".equals(finalStatus)) {
            return ResponseEntity.status(502).body(body);
        }
        return ResponseEntity.ok(body);
    }
    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String,Object>> stopApplication(@PathVariable Long id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @RequestParam Long userId) {
        idempotencyService.checkAndStore(idempotencyKey, userId, id);
        jdbc.update("UPDATE applications SET status='STOPPING' WHERE id=?", id);

        Map<String, Object> app = jdbc.queryForMap(
            "SELECT server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, stop_script FROM applications WHERE id = ?",
            id
        );
        String host = app.get("server_ip").toString();
        String user = (String) app.get("ssh_username");
        String encPass = (String) app.get("ssh_password_enc");
        String fingerprint = (String) app.get("ssh_host_key_fingerprint");
        String stopScript = (String) app.get("stop_script");

        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, stopScript, 120000);
            session.disconnect();
            if (result.exitCode == 0) {
                jdbc.update("UPDATE applications SET status='STOPPED' WHERE id=?", id);
            } else {
                jdbc.update("UPDATE applications SET status='ERROR' WHERE id=?", id);
            }
        } catch (Exception e) {
            log.error("Failed to start/stop application id={}", id, e);
            jdbc.update("UPDATE applications SET status='ERROR' WHERE id=?", id);
        }

        String finalStatus = jdbc.queryForObject("SELECT status FROM applications WHERE id=?", String.class, id);
        Map<String,Object> body = new java.util.HashMap<>();
        body.put("status", finalStatus);
        if ("ERROR".equals(finalStatus)) {
            return ResponseEntity.status(502).body(body);
        }
        return ResponseEntity.ok(body);
    }
    @PutMapping("/{id}")
    public ResponseEntity<Void> updateApplication(@PathVariable Long id, @RequestBody Map<String,Object> req) {
        String status = jdbc.queryForObject("SELECT status FROM applications WHERE id = ?", String.class, id);
        boolean isOffline = "STOPPED".equals(status) || "ERROR".equals(status);
        if (!isOffline) return ResponseEntity.status(403).build();

        String name = (String) req.get("name");
        String serverIp = (String) req.get("serverIp");
        String sshUsername = (String) req.get("sshUsername");
        String startScript = (String) req.get("startScript");
        String stopScript = (String) req.get("stopScript");
        String logScript = (String) req.get("logScript");
        Object rawPoll = req.getOrDefault("pollIntervalSeconds", 5);
        int pollInterval = ((Number) rawPoll).intValue();

        String sshPassword = (String) req.get("sshPassword");
        if (sshPassword != null && !sshPassword.isBlank()) {
            String encPassword = cipher.encrypt(sshPassword);
            jdbc.update(
                "UPDATE applications SET name=?, server_ip=?::inet, ssh_username=?, ssh_password_enc=?, start_script=?, stop_script=?, log_script=?, poll_interval_seconds=?, updated_at=NOW() WHERE id=?",
                name, serverIp, sshUsername, encPassword, startScript, stopScript, logScript, pollInterval, id);
        } else {
            jdbc.update(
                "UPDATE applications SET name=?, server_ip=?::inet, ssh_username=?, start_script=?, stop_script=?, log_script=?, poll_interval_seconds=?, updated_at=NOW() WHERE id=?",
                name, serverIp, sshUsername, startScript, stopScript, logScript, pollInterval, id);
        }
        return ResponseEntity.ok().build();
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApplication(@PathVariable Long id) {
        String status = jdbc.queryForObject("SELECT status FROM applications WHERE id = ?", String.class, id);
        boolean isOffline = "STOPPED".equals(status) || "ERROR".equals(status);
        if (!isOffline) return ResponseEntity.status(409).build();
        jdbc.update("DELETE FROM applications WHERE id = ?", id);
        return ResponseEntity.noContent().build();
    }
}