package com.bpl.orderapp.admin.audit;

import com.bpl.orderapp.admin.application.ApplicationRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Writes audit rows. Every write is REQUIRES_NEW (a separate transaction), and the
 * database is SQLite, which has a single writer.
 *
 * <p>RULE: never call this from inside an open write transaction. The caller's
 * transaction holds the write lock, the audit insert waits for it on another
 * connection, and fails with SQLITE_BUSY after busy_timeout. Do the database work in
 * a {@code @Transactional} service method, let it return (commit and release its
 * connection), then audit from the caller. See UserUpdateService / UserController
 * and AuditTransactionHazardTest.
 */
public class AuditWriter {
    private final AuditLogRepository auditLogRepository;
    private final ApplicationRepository applicationRepository;
    private final com.bpl.orderapp.admin.ratelimit.ClientAddress clientAddress;

    public AuditWriter(AuditLogRepository auditLogRepository, ApplicationRepository applicationRepository,
            com.bpl.orderapp.admin.ratelimit.ClientAddress clientAddress) {
        this.clientAddress = clientAddress;
        this.auditLogRepository = auditLogRepository;
        this.applicationRepository = applicationRepository;
    }

    private static final Set<String> SECRET_KEYS =
        Set.of("ssh_password_enc", "temporaryPassword", "password_hash", "password");

    /**
     * Allowed audit_logs.action_type values. There is no DB CHECK on this column
     * (SQLite cannot change a CHECK without rebuilding the table), so this set is
     * the guard. Adding an action means adding it here.
     */
    static final Set<String> ALLOWED_ACTIONS = Set.of(
        "START_APPLICATION", "STOP_APPLICATION", "CREATE_APPLICATION", "UPDATE_APPLICATION",
        "DELETE_APPLICATION", "ASSIGN_APPLICATION", "CREATE_USER", "CREATE_ADMIN", "UPDATE_USER",
        "DELETE_USER", "RESET_PASSWORD", "CHANGE_PASSWORD", "LOGIN", "LOGOUT",
        "SHELLCHECK_UNAVAILABLE", "APPLICATION_ONLINE", "APPLICATION_OFFLINE");

    /** Back-compat overload for any call site that genuinely has no
     *  request in scope. Writes source_ip = NULL. Prefer the
     *  HttpServletRequest overload below wherever a request is available. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String actionType, String actorUsername, String actorRole, Long targetAppId, String targetAppName, java.util.UUID targetUserId, Map<String,Object> detail, String result) {
        insert(Instant.now(), actionType, actorUsername, actorRole, targetAppId, targetAppName, targetUserId, detail, result, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String actionType, String actorUsername, String actorRole, Long targetAppId, String targetAppName, java.util.UUID targetUserId, Map<String,Object> detail, String result, HttpServletRequest request) {
        insert(Instant.now(), actionType, actorUsername, actorRole, targetAppId, targetAppName, targetUserId, detail, result, extractSourceIp(request));
    }

    /** Writes an audit row with an explicit event time (used when the event is detected after the fact, e.g. browser closed). source_ip = NULL. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAt(java.time.Instant when, String actionType, String actorUsername, String actorRole, Map<String,Object> detail, String result) {
        insert(when, actionType, actorUsername, actorRole, null, null, null, detail, result, null);
    }

    private void insert(Instant when, String actionType, String actorUsername, String actorRole,
                        Long targetAppId, String targetAppName, UUID targetUserId,
                        Map<String, Object> detail, String result, String sourceIp) {
        if (!ALLOWED_ACTIONS.contains(actionType)) {
            throw new IllegalArgumentException("Unknown audit action type: " + actionType);
        }
        if (targetAppName == null && targetAppId != null) {
            try {
                targetAppName = applicationRepository.findNameById(targetAppId).orElse(null);
            } catch (Exception ignored) { /* application already gone */ }
        }
        Map<String, Object> safe = detail == null ? new HashMap<>() : new HashMap<>(detail);
        SECRET_KEYS.forEach(safe::remove);
        auditLogRepository.save(new AuditLog(when, actorUsername, actorRole, actionType,
            targetAppId, targetAppName, targetUserId, safe, result, sourceIp));
    }

    /**
     * Caddy is the ONLY reverse-proxy hop in front of this backend
     * (client -> Caddy -> backend, per the Caddyfile). Caddy does
     * NOT overwrite an inbound X-Forwarded-For; it APPENDS its own
     * observed peer address to whatever the client sent. That means
     * the first entry in this header is attacker-controlled (a
     * client can send any X-Forwarded-For it likes), while the LAST
     * entry is the address Caddy itself saw on the socket - the one
     * value in this header that's actually trustworthy here. Taking
     * the first entry (a common mistake) would let any caller spoof
     * their audited source_ip. If another trusted proxy is ever
     * added in front of Caddy, this must change to strip exactly
     * that many trusted hops from the end instead of always taking
     * the last one.
     */
    /** Same rule as rate limiting: X-Forwarded-For counts only behind a trusted proxy. */
    private String extractSourceIp(HttpServletRequest request) {
        return request == null ? null : clientAddress.of(request);
    }
}