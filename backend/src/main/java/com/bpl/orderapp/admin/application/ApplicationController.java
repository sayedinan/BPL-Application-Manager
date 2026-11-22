package com.bpl.orderapp.admin.application;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.IdempotencyService;
import com.bpl.orderapp.admin.common.NotFoundException;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.audit.AuditLogReader;
import com.bpl.orderapp.admin.log.ApplicationLogLine;
import com.bpl.orderapp.admin.log.ApplicationLogLineRepository;
import com.bpl.orderapp.admin.log.LogPollingOrchestrator;
import com.bpl.orderapp.admin.health.ApplicationHealthConfigRepository;
import com.bpl.orderapp.admin.security.AccessGuard;
import com.bpl.orderapp.admin.security.Action;
import com.bpl.orderapp.admin.ssh.SshConnection;
import com.bpl.orderapp.admin.status.ApplicationStatusStreak;
import com.bpl.orderapp.admin.status.ApplicationStatusStreakRepository;
import com.bpl.orderapp.admin.status.ApplicationStatusTransition;
import com.bpl.orderapp.admin.status.ApplicationStatusTransitionRepository;
import com.bpl.orderapp.admin.status.StatusConfirmationTimeoutException;
import com.bpl.orderapp.admin.status.StatusPollingOrchestrator;
import com.bpl.orderapp.admin.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per STATUS-REDESIGN.md: applications.status no longer exists.
 * Online/offline is derived live by {@link StatusPollingOrchestrator},
 * fed by each application's health endpoint. This controller never
 * writes a status value — it only triggers start/stop scripts and
 * waits for the poller to confirm the resulting state.
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApplicationController.class);

    // STATUS-REDESIGN.md §3 — different timeouts for start vs stop,
    // since shutdown is generally faster/more deterministic than boot.
    private static final long START_CONFIRMATION_TIMEOUT_MS = 60_000;
    private static final long STOP_CONFIRMATION_TIMEOUT_MS = 15_000;
    private static final long CONFIRMATION_POLL_INTERVAL_MS = 1_000;

    private final AuditLogReader auditLogReader;
    private final SshCredentialCipher cipher;
    private final IdempotencyService idempotencyService;
    private final SshConnection sshConnection;
    private final AuditWriter auditWriter;
    private final LogPollingOrchestrator logPollingOrchestrator;
    private final StatusPollingOrchestrator statusPollingOrchestrator;
    private final java.util.List<com.bpl.orderapp.admin.notification.Notifier> notifiers;
    private final AccessGuard accessGuard;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationStatusStreakRepository streakRepository;
    private final ApplicationStatusTransitionRepository transitionRepository;
    private final ApplicationHealthConfigRepository healthConfigRepository;
    private final ApplicationLogLineRepository logLineRepository;

    public ApplicationController(SshCredentialCipher cipher,
            IdempotencyService idempotencyService, SshConnection sshConnection,
            AuditWriter auditWriter, LogPollingOrchestrator logPollingOrchestrator,
            StatusPollingOrchestrator statusPollingOrchestrator,
            java.util.List<com.bpl.orderapp.admin.notification.Notifier> notifiers,
            AccessGuard accessGuard, UserRepository userRepository,
            ApplicationRepository applicationRepository,
            ApplicationStatusStreakRepository streakRepository,
            ApplicationStatusTransitionRepository transitionRepository,
            ApplicationHealthConfigRepository healthConfigRepository,
            ApplicationLogLineRepository logLineRepository,
            AuditLogReader auditLogReader) {
        this.auditLogReader = auditLogReader;
        this.cipher = cipher;
        this.idempotencyService = idempotencyService;
        this.sshConnection = sshConnection;
        this.auditWriter = auditWriter;
        this.logPollingOrchestrator = logPollingOrchestrator;
        this.statusPollingOrchestrator = statusPollingOrchestrator;
        this.notifiers = notifiers;
        this.accessGuard = accessGuard;
        this.userRepository = userRepository;
        this.applicationRepository = applicationRepository;
        this.streakRepository = streakRepository;
        this.transitionRepository = transitionRepository;
        this.healthConfigRepository = healthConfigRepository;
        this.logLineRepository = logLineRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new com.bpl.orderapp.admin.common.InvalidCredentialsException();
        }
        String username = auth.getPrincipal().toString();
        com.bpl.orderapp.admin.user.User user = userRepository.findByUsername(username)
            .orElseThrow(com.bpl.orderapp.admin.common.InvalidCredentialsException::new);
        java.util.UUID userId = user.getId();
        String role = user.getRole();

        // A just-created application may not have a streak row yet (its first
        // status check hasn't landed); the repository returns null online for
        // it, which reads as offline here.
        List<ApplicationListRow> rows = ("SYS_ADMIN".equals(role) || "ADMIN".equals(role))
            ? applicationRepository.findAllWithStatus()
            : applicationRepository.findAssignedWithStatus(userId);

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (ApplicationListRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("name", row.name());
            item.put("groupName", row.groupName());
            boolean online = Boolean.TRUE.equals(row.online());
            item.put("online", online);
            // Instant.toString() is ISO-8601 with a "Z", so the browser parses it
            // as UTC. Only meaningful while online (null while stopped).
            item.put("startedAt", (online && row.streakStartedAt() != null)
                ? row.streakStartedAt().toString()
                : null);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getApplication(@PathVariable Long id) {
        accessGuard.require(Action.ACCESS_APPLICATION);
        Application app = applicationRepository.findById(id).orElseThrow(NotFoundException::new);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", app.getId());
        body.put("name", app.getName());
        // getHostAddress(), not toString(): InetAddress.toString() adds a leading "/".
        body.put("serverIp", app.getServerIp().getHostAddress());
        body.put("sshUsername", app.getSshUsername());
        body.put("sshHostKeyFingerprint", app.getSshHostKeyFingerprint());
        body.put("startScript", app.getStartScript());
        body.put("emailStartMessage", app.getEmailStartMessage());
        body.put("emailStopMessage", app.getEmailStopMessage());
        body.put("emailExternalOnlineMessage", app.getEmailExternalOnlineMessage());
        body.put("emailExternalOfflineMessage", app.getEmailExternalOfflineMessage());
        body.put("smsStartMessage", app.getSmsStartMessage());
        body.put("smsStopMessage", app.getSmsStopMessage());
        body.put("smsExternalOnlineMessage", app.getSmsExternalOnlineMessage());
        body.put("smsExternalOfflineMessage", app.getSmsExternalOfflineMessage());
        body.put("stopScript", app.getStopScript());
        body.put("logScript", app.getLogScript());
        body.put("online", statusPollingOrchestrator.isOnline(id));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable Long id) {
        accessGuard.requireApplication(Action.LIST_APPLICATIONS, id);
        java.sql.Timestamp createdAt = java.sql.Timestamp.from(
            applicationRepository.findCreatedAtById(id).orElseThrow(NotFoundException::new));

        // Walk every recorded flip chronologically and sum the duration
        // spent in each state. A brand-new app with zero transitions yet
        // is assumed offline from creation (it starts stopped by design).
        List<ApplicationStatusTransition> transitions =
            transitionRepository.findByApplicationIdOrderByTransitionedAtAsc(id);

        long uptimeSeconds = 0;
        long downtimeSeconds = 0;
        Instant cursor = createdAt.toInstant();
        boolean state = false;
        for (ApplicationStatusTransition t : transitions) {
            Instant flipAt = t.getTransitionedAt();
            long delta = flipAt.getEpochSecond() - cursor.getEpochSecond();
            if (state) uptimeSeconds += delta; else downtimeSeconds += delta;
            cursor = flipAt;
            state = t.isOnline();
        }
        long tail = Instant.now().getEpochSecond() - cursor.getEpochSecond();
        if (state) uptimeSeconds += tail; else downtimeSeconds += tail;

        java.util.Optional<ApplicationStatusStreak> streak = streakRepository.findById(id);

        java.util.Optional<Instant> lastRan = auditLogReader.lastStartedAt(id);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("createdAt", createdAt.toInstant().toString());
        body.put("totalUptimeSeconds", uptimeSeconds);
        body.put("totalDowntimeSeconds", downtimeSeconds);
        body.put("currentlyOnline", streak.map(ApplicationStatusStreak::isOnline).orElse(false));
        body.put("currentStreakStartedAt",
            streak.map(s -> s.getStreakStartedAt().toString()).orElse(null));
        body.put("lastRanAt", lastRan.map(Instant::toString).orElse(null));
        body.put("lastWentOnlineAt", lastTransitionAt(id, true));
        body.put("lastWentOfflineAt", lastTransitionAt(id, false));
        body.put("recentTransitions", recentTransitions(id, 10));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, Object> req) {
        accessGuard.require(Action.UPDATE_APPLICATION);
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
            com.jcraft.jsch.Session session = sshConnection.openUncached(serverIp, sshUsername, plainPass, sshHostKeyFingerprint);
            String presentedFingerprint;
            try {
                presentedFingerprint = session.getHostKey().getFingerPrint(new com.jcraft.jsch.JSch());
            } finally {
                session.disconnect();
            }
            Map<String, Object> ok = new HashMap<>();
            ok.put("status", "OK");
            ok.put("fingerprint", presentedFingerprint);
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            log.error("SSH connection test failed for server={}", serverIp, e);
            Map<String, Object> fail = new HashMap<>();
            fail.put("status", "SSH_CONNECTION_FAILED");
            fail.put("message", e instanceof SecurityException ? "SSH host key fingerprint mismatch" : "Could not establish an SSH connection with the supplied details");
            return ResponseEntity.status(502).body(fail);
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<List<Map<String, Object>>> getLogs(@PathVariable Long id) {
        accessGuard.requireApplication(Action.LIST_APPLICATIONS, id);
        // Same JSON keys as before (line_number, content, captured_at); the
        // frontend's LogsBox reads them. captured_at is now an ISO-8601 instant.
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (ApplicationLogLine line : logLineRepository.findByApplicationIdOrderByLineNumberAsc(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line_number", line.getLineNumber());
            row.put("content", line.getContent());
            row.put("captured_at", line.getCapturedAt().toString());
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    @PostMapping
    public ResponseEntity<Void> createApplication(@RequestBody Map<String, Object> req, HttpServletRequest httpRequest) {
        accessGuard.require(Action.CREATE_APPLICATION);
        String name = (String) req.get("name");
        String serverIp = (String) req.get("serverIp");
        java.net.InetAddress serverAddress = IpAddresses.parseIpv4(serverIp);
        if (name != null && applicationRepository.existsByNameIgnoreCase(name)) {
            throw new com.bpl.orderapp.admin.common.DuplicateNameException(name);
        }
        String sshUsername = (String) req.get("sshUsername");
        String sshPassword = (String) req.get("sshPassword");
        String startScript = (String) req.get("startScript");
        String stopScript = (String) req.get("stopScript");
        String logScript = (String) req.get("logScript");
        String emailStartMessage = (String) req.get("emailStartMessage");
        String emailStopMessage = (String) req.get("emailStopMessage");
        String emailExternalOnlineMessage = (String) req.get("emailExternalOnlineMessage");
        String emailExternalOfflineMessage = (String) req.get("emailExternalOfflineMessage");
        String smsStartMessage = (String) req.get("smsStartMessage");
        String smsStopMessage = (String) req.get("smsStopMessage");
        String smsExternalOnlineMessage = (String) req.get("smsExternalOnlineMessage");
        String smsExternalOfflineMessage = (String) req.get("smsExternalOfflineMessage");
        rejectIfAnySmsTooLong(smsStartMessage, smsStopMessage, smsExternalOnlineMessage, smsExternalOfflineMessage);
        String encPassword = cipher.encrypt(sshPassword);
        validateScripts(httpRequest, startScript, stopScript, logScript);

        Application newApp = new Application();
        newApp.setName(name);
        newApp.setServerIp(serverAddress);
        newApp.setSshUsername(sshUsername);
        newApp.setSshPasswordEnc(encPassword);
        newApp.setSshHostKeyFingerprint((String) req.get("sshHostKeyFingerprint"));
        newApp.setStartScript(startScript);
        newApp.setStopScript(stopScript);
        newApp.setLogScript(logScript);
        newApp.setEmailStartMessage(emailStartMessage);
        newApp.setEmailStopMessage(emailStopMessage);
        newApp.setEmailExternalOnlineMessage(emailExternalOnlineMessage);
        newApp.setEmailExternalOfflineMessage(emailExternalOfflineMessage);
        newApp.setSmsStartMessage(smsStartMessage);
        newApp.setSmsStopMessage(smsStopMessage);
        newApp.setSmsExternalOnlineMessage(smsExternalOnlineMessage);
        newApp.setSmsExternalOfflineMessage(smsExternalOfflineMessage);
        Long newId = applicationRepository.save(newApp).getId();

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
            HttpServletRequest httpRequest) {
        accessGuard.requireApplication(Action.CONTROL_APPLICATION, id);
        requireHealthMonitoring(id);
        idempotencyService.checkAndStore(idempotencyKey, accessGuard.currentCaller().userId(), id);

        Application app = applicationRepository.findById(id).orElseThrow(NotFoundException::new);
        // getHostAddress(), not toString(): toString() would give "/10.0.0.5" and SSH would fail.
        String host = app.getServerIp().getHostAddress();
        String user = app.getSshUsername();
        String encPass = app.getSshPasswordEnc();
        String fingerprint = app.getSshHostKeyFingerprint();
        String startScript = app.getStartScript();

        try {
            var startActor = currentActor();
            statusPollingOrchestrator.expectAction(id, startActor.get("username"), startActor.get("role"), true);
        } catch (Exception actorEx) {
            log.warn("Could not resolve actor for start of application id={}", id, actorEx);
        }

        statusPollingOrchestrator.pauseFor(id);
        boolean scriptSucceeded;
        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, startScript, 120000);
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
            statusPollingOrchestrator.clearExpectedAction(id);
            Map<String, Object> body = new HashMap<>();
            body.put("code", "SSH_COMMAND_FAILED");
            return ResponseEntity.status(502).body(body);
        }

        boolean confirmed = waitForConfirmedStatus(id, true, START_CONFIRMATION_TIMEOUT_MS);
        statusPollingOrchestrator.clearExpectedAction(id);
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
        notifyWebAppAction(id, com.bpl.orderapp.admin.notification.LifecycleEventType.STARTED_VIA_DASHBOARD);

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopApplication(@PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        accessGuard.requireApplication(Action.CONTROL_APPLICATION, id);
        requireHealthMonitoring(id);
        idempotencyService.checkAndStore(idempotencyKey, accessGuard.currentCaller().userId(), id);

        Application app = applicationRepository.findById(id).orElseThrow(NotFoundException::new);
        String host = app.getServerIp().getHostAddress();
        String user = app.getSshUsername();
        String encPass = app.getSshPasswordEnc();
        String fingerprint = app.getSshHostKeyFingerprint();
        String stopScript = app.getStopScript();

        try {
            var stopActor = currentActor();
            statusPollingOrchestrator.expectAction(id, stopActor.get("username"), stopActor.get("role"), false);
        } catch (Exception actorEx) {
            log.warn("Could not resolve actor for stop of application id={}", id, actorEx);
        }

        statusPollingOrchestrator.pauseFor(id);
        boolean scriptSucceeded;
        try {
            String plainPass = cipher.decrypt(encPass);
            com.jcraft.jsch.Session session = sshConnection.connect(host, user, plainPass, fingerprint);
            SshConnection.SshResult result = sshConnection.runScriptWithTimeout(session, stopScript, 120000);
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
            statusPollingOrchestrator.clearExpectedAction(id);
            Map<String, Object> body = new HashMap<>();
            body.put("code", "SSH_COMMAND_FAILED");
            return ResponseEntity.status(502).body(body);
        }

        boolean confirmed = waitForConfirmedStatus(id, false, STOP_CONFIRMATION_TIMEOUT_MS);
        statusPollingOrchestrator.clearExpectedAction(id);
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
        notifyWebAppAction(id, com.bpl.orderapp.admin.notification.LifecycleEventType.STOPPED_VIA_DASHBOARD);

        return ResponseEntity.ok(body);
    }

    // Best-effort: notification failures must never affect the
    // start/stop flow above (audit write already happened, response
    // already prepared) — hence its own try/catch rather than
    // sharing the caller's, and called outside that caller's own
    // try/catch so a mail failure never gets logged as if it were an
    // audit-write failure.
    private void notifyWebAppAction(Long applicationId,
            com.bpl.orderapp.admin.notification.LifecycleEventType eventType) {
        if (notifiers.isEmpty()) {
            return;
        }
        try {
            var actor = currentActor();
            applicationRepository.findNameById(applicationId)
                .ifPresent(appName -> {
                    for (com.bpl.orderapp.admin.notification.Notifier notifier : notifiers) {
                        try {
                            notifier.notifyLifecycleEvent(
                                applicationId, appName, eventType, actor.get("username"), actor.get("role"), null);
                        } catch (Exception e) {
                            log.warn("Notification dispatch failed ({}) for application id={}",
                                notifier.getClass().getSimpleName(), applicationId, e);
                        }
                    }
                });
        } catch (Exception e) {
            log.warn("Notification dispatch failed for application id={}", applicationId, e);
        }
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

    /**
     * ShellCheck gate (§12.3). Error-severity findings reject the save (400
     * SHELLCHECK_FAILED via ShellCheckFailedException). Fail-open ONLY when
     * ShellCheck itself is unavailable, and that case is audited.
     */
    private void validateScripts(HttpServletRequest httpRequest, String... scripts) {
        ShellCheckValidator validator = new ShellCheckValidator();
        try {
            for (String sc : scripts) validator.validate(sc);
        } catch (ShellCheckValidator.ShellCheckUnavailableException e) {
            log.warn("ShellCheck unavailable; saving unvalidated scripts", e);
            try {
                var actor = currentActor();
                auditWriter.write("SHELLCHECK_UNAVAILABLE", actor.get("username"), actor.get("role"),
                    null, null, null, Map.of("note", "scripts saved without validation"), "SUCCESS", httpRequest);
            } catch (Exception auditEx) {
                log.warn("Audit write failed for SHELLCHECK_UNAVAILABLE", auditEx);
            }
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> updateApplication(@PathVariable Long id, @RequestBody Map<String, Object> req, HttpServletRequest httpRequest) {
        accessGuard.require(Action.UPDATE_APPLICATION);
        if (statusPollingOrchestrator.isOnline(id)) {
            return ResponseEntity.status(403).build();
        }

        String name = (String) req.get("name");
        String serverIp = (String) req.get("serverIp");
        String sshUsername = (String) req.get("sshUsername");
        String startScript = (String) req.get("startScript");
        String stopScript = (String) req.get("stopScript");
        String logScript = (String) req.get("logScript");
        String emailStartMessage = (String) req.get("emailStartMessage");
        String emailStopMessage = (String) req.get("emailStopMessage");
        String emailExternalOnlineMessage = (String) req.get("emailExternalOnlineMessage");
        String emailExternalOfflineMessage = (String) req.get("emailExternalOfflineMessage");
        String smsStartMessage = (String) req.get("smsStartMessage");
        String smsStopMessage = (String) req.get("smsStopMessage");
        String smsExternalOnlineMessage = (String) req.get("smsExternalOnlineMessage");
        String smsExternalOfflineMessage = (String) req.get("smsExternalOfflineMessage");
        rejectIfAnySmsTooLong(smsStartMessage, smsStopMessage, smsExternalOnlineMessage, smsExternalOfflineMessage);
        java.net.InetAddress serverAddress = IpAddresses.parseIpv4(serverIp);
        if (name != null && applicationRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new com.bpl.orderapp.admin.common.DuplicateNameException(name);
        }
        Application app = applicationRepository.findById(id).orElseThrow(NotFoundException::new);

        String sshPassword = (String) req.get("sshPassword");
        validateScripts(httpRequest, startScript, stopScript, logScript);
        app.setName(name);
        app.setServerIp(serverAddress);
        app.setSshUsername(sshUsername);
        String newFingerprint = (String) req.get("sshHostKeyFingerprint");
        if (newFingerprint != null && !newFingerprint.isBlank()) {
            app.setSshHostKeyFingerprint(newFingerprint.trim());
        }
        // A blank password means "keep the stored one".
        if (sshPassword != null && !sshPassword.isBlank()) {
            app.setSshPasswordEnc(cipher.encrypt(sshPassword));
        }
        app.setStartScript(startScript);
        app.setStopScript(stopScript);
        app.setLogScript(logScript);
        app.setEmailStartMessage(emailStartMessage);
        app.setEmailStopMessage(emailStopMessage);
        app.setEmailExternalOnlineMessage(emailExternalOnlineMessage);
        app.setEmailExternalOfflineMessage(emailExternalOfflineMessage);
        app.setSmsStartMessage(smsStartMessage);
        app.setSmsStopMessage(smsStopMessage);
        app.setSmsExternalOnlineMessage(smsExternalOnlineMessage);
        app.setSmsExternalOfflineMessage(smsExternalOfflineMessage);
        applicationRepository.save(app);

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
        accessGuard.require(Action.DELETE_APPLICATION);
        if (statusPollingOrchestrator.isOnline(id)) {
            return ResponseEntity.status(409).build();
        }

        Application app = applicationRepository.findById(id).orElseThrow(NotFoundException::new);
        String host = app.getServerIp().getHostAddress();
        String user = app.getSshUsername();
        String fingerprint = app.getSshHostKeyFingerprint();

        statusPollingOrchestrator.stopPolling(id);
        logPollingOrchestrator.stopPolling(id);
        // Both cached connections must be closed — status polling and
        // log polling now live on independent sessions (SshConnection
        // "status" vs "default" purpose; see the ssh-connection-handling
        // skill update this implies).
        sshConnection.closeConnection(host, user, fingerprint, "status");
        sshConnection.closeConnection(host, user, fingerprint);

        String deletedName = app.getName();
        applicationRepository.deleteById(id);

        try {
            var actor = currentActor();
            auditWriter.write("DELETE_APPLICATION", actor.get("username"), actor.get("role"),
                id, deletedName, null, Map.of(), "SUCCESS", httpRequest);
        } catch (Exception auditEx) {
            log.warn("Audit write failed for DELETE_APPLICATION (id={})", id, auditEx);
        }

        return ResponseEntity.noContent().build();
    }

    private String lastTransitionAt(Long id, boolean online) {
        return transitionRepository
            .findFirstByApplicationIdAndOnlineOrderByTransitionedAtDesc(id, online)
            .map(t -> t.getTransitionedAt().toString())
            .orElse(null);
    }

    private List<Map<String, Object>> recentTransitions(Long id, int limit) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ApplicationStatusTransition t : transitionRepository.findByApplicationIdOrderByTransitionedAtDesc(
                id, org.springframework.data.domain.PageRequest.of(0, limit))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("online", t.isOnline());
            row.put("at", t.getTransitionedAt().toString());
            out.add(row);
        }
        return out;
    }

    private void requireHealthMonitoring(Long id) {
        if (!healthConfigRepository.existsByApplicationIdAndEnabledTrue(id)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Set up health monitoring for this application before starting or stopping it.");
        }
    }

    private void rejectIfAnySmsTooLong(String... smsMessages) {
        for (String msg : smsMessages) {
            if (msg != null && msg.length() > 160) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "SMS message templates must be 160 characters or fewer");
            }
        }
    }

    private Map<String, String> currentActor() {
        AccessGuard.Caller caller = accessGuard.currentCaller();
        return Map.of("username", caller.username(), "role", caller.role().name());
    }
}
