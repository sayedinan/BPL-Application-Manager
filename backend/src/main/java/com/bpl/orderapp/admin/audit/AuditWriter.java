package com.bpl.orderapp.admin.audit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
public class AuditWriter {
    private final JdbcTemplate jdbc;
    public AuditWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Back-compat overload for any call site that genuinely has no
     *  request in scope. Writes source_ip = NULL. Prefer the
     *  HttpServletRequest overload below wherever a request is available. */
    public void write(String actionType, String actorUsername, String actorRole, Long targetAppId, String targetAppName, Long targetUserId, Map<String,Object> detail, String result) {
        insert(actionType, actorUsername, actorRole, targetAppId, targetAppName, targetUserId, detail, result, null);
    }

    public void write(String actionType, String actorUsername, String actorRole, Long targetAppId, String targetAppName, Long targetUserId, Map<String,Object> detail, String result, HttpServletRequest request) {
        insert(actionType, actorUsername, actorRole, targetAppId, targetAppName, targetUserId, detail, result, extractSourceIp(request));
    }

    /** Writes an audit row with an explicit event time (used when the event is detected after the fact, e.g. browser closed). source_ip = NULL. */
    public void writeAt(java.time.Instant when, String actionType, String actorUsername, String actorRole, Map<String,Object> detail, String result) {
        jdbc.update("INSERT INTO audit_logs (timestamp,actor_username,actor_role,action_type,detail,result,source_ip) VALUES (?,?,?,?,?::jsonb,?,NULL)",
            java.sql.Timestamp.from(when), actorUsername, actorRole, actionType, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(new HashMap<>(detail)).toString(), result);
    }

    private void insert(String actionType, String actorUsername, String actorRole, Long targetAppId, String targetAppName, Long targetUserId, Map<String,Object> detail, String result, String sourceIp) {
        Map<String,Object> safe = new HashMap<>(detail);
        safe.remove("ssh_password_enc"); safe.remove("temporaryPassword"); safe.remove("password_hash"); safe.remove("password");
        jdbc.update("INSERT INTO audit_logs (timestamp,actor_username,actor_role,action_type,target_application_id,target_application_name,target_user_id,detail,result,source_ip) VALUES (NOW(),?,?,?,?,?,?,?::jsonb,?,?)",
            actorUsername, actorRole, actionType, targetAppId, targetAppName, targetUserId, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(safe).toString(), result, sourceIp);
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
