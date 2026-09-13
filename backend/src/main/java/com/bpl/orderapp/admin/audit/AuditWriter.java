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

    private void insert(String actionType, String actorUsername, String actorRole, Long targetAppId, String targetAppName, Long targetUserId, Map<String,Object> detail, String result, String sourceIp) {
        Map<String,Object> safe = new HashMap<>(detail);
        safe.remove("ssh_password_enc"); safe.remove("temporaryPassword"); safe.remove("password_hash"); safe.remove("password");
        jdbc.update("INSERT INTO audit_logs (timestamp,actor_username,actor_role,action_type,target_application_id,target_application_name,target_user_id,detail,result,source_ip) VALUES (NOW(),?,?,?,?,?,?,?::jsonb,?,?)",
            actorUsername, actorRole, actionType, targetAppId, targetAppName, targetUserId, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(safe).toString(), result, sourceIp);
    }

    /**
     * Caddy sits in front of the backend as a reverse proxy, so
     * request.getRemoteAddr() would always report Caddy's own
     * address, not the real client. Caddy forwards the original
     * client IP in X-Forwarded-For; take the first (left-most)
     * entry, since that's the original client — everything after it
     * is intermediate proxy hops. Fall back to getRemoteAddr() for
     * direct connections (e.g. local dev without Caddy in front).
     */
    private String extractSourceIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
