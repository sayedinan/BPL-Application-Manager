package com.bpl.orderapp.admin.audit;

import com.bpl.orderapp.admin.application.ApplicationRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

public class AuditWriter {
    private final AuditLogRepository auditLogRepository;
    private final ApplicationRepository applicationRepository;

    public AuditWriter(AuditLogRepository auditLogRepository, ApplicationRepository applicationRepository) {
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
    private String extractSourceIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}