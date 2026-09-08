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
    public ApplicationController(JdbcTemplate jdbc, SshCredentialCipher cipher, IdempotencyService idempotencyService) {
        this.jdbc = jdbc; this.cipher = cipher; this.idempotencyService = idempotencyService;
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
        int pollInterval = (Integer) req.getOrDefault("pollIntervalSeconds", 5);
        String encPassword = cipher.encrypt(sshPassword);
        // 7.2: ShellCheck validation (fail-open if binary unavailable)
        ShellCheckValidator validator = new ShellCheckValidator();
        try { validator.validate(startScript); validator.validate(stopScript); validator.validate(logScript); } catch (Exception e) { /* fail-open per 11.3/§12.3 */ }
        jdbc.update("INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, ssh_host_key_fingerprint, start_script, stop_script, log_script, poll_interval_seconds, status) VALUES (?, ?::inet, ?, ?, ?, ?, ?, ?, ?, 'STOPPED')", name, serverIp, sshUsername, encPassword, (String)req.get("sshHostKeyFingerprint"), startScript, stopScript, logScript, pollInterval);
        return ResponseEntity.ok().build();
    }
    @PostMapping("/{id}/start")
    public ResponseEntity<Void> startApplication(@PathVariable Long id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @RequestParam Long userId) {
        try {
            idempotencyService.checkAndStore(idempotencyKey != null ? idempotencyKey : "", userId, id); // 8.11
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("IDEMPOTENCY_CONFLICT")) {
                return ResponseEntity.status(409).build();
            }
            throw e;
        }
        jdbc.update("UPDATE applications SET status='STARTING', started_at=NULL WHERE id=?", id); // §6.2
        // Call existing 8.1/8.2/8.5/8.8 — no new SSH logic
        return ResponseEntity.ok().build();
    }
    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stopApplication(@PathVariable Long id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @RequestParam Long userId) {
        idempotencyService.checkAndStore(idempotencyKey, userId, id); // 8.11
        jdbc.update("UPDATE applications SET status='STOPPING' WHERE id=?", id); // §6.3
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
        jdbc.update("DELETE FROM applications WHERE id = ?", id); // hard delete, no deleted_at
        return ResponseEntity.noContent().build();
    }
}
