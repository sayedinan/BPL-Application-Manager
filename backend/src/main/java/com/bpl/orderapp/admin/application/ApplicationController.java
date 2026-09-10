package com.bpl.orderapp.admin.application;
import org.springframework.security.access.prepost.PreAuthorize;

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
    @PostMapping
    public ResponseEntity<Void> createApplication(@RequestBody Map<String, Object> req) {
        String name = (String) req.get("name");
        String serverIp = (String) req.get("serverIp");
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
    public ResponseEntity<Void> startApplication(@PathVariable Long id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @RequestParam Long userId) {
        try {
            idempotencyService.checkAndStore(idempotencyKey != null ? idempotencyKey : "", userId, id);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("IDEMPOTENCY_CONFLICT")) {
                return ResponseEntity.status(409).build();
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
            jdbc.update("UPDATE applications SET status='ERROR' WHERE id=?", id);
        }

        return ResponseEntity.ok().build();
    }
    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stopApplication(@PathVariable Long id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @RequestParam Long userId) {
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
            jdbc.update("UPDATE applications SET status='ERROR' WHERE id=?", id);
        }

        return ResponseEntity.ok().build();
    }
    @PutMapping("/{id}")
    public ResponseEntity<Void> updateApplication(@PathVariable Long id, @RequestBody Map<String,Object> req) {
        String status = jdbc.queryForObject("SELECT status FROM applications WHERE id = ?", String.class, id);
        if (!"STOPPED".equals(status)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok().build();
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApplication(@PathVariable Long id) {
        String status = jdbc.queryForObject("SELECT status FROM applications WHERE id = ?", String.class, id);
        if (!"STOPPED".equals(status)) return ResponseEntity.status(409).build();
        jdbc.update("DELETE FROM applications WHERE id = ?", id);
        return ResponseEntity.noContent().build();
    }
}
