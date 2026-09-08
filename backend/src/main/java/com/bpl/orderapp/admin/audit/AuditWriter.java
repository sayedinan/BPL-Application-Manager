package com.bpl.orderapp.admin.audit;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
public class AuditWriter {
    private final JdbcTemplate jdbc;
    public AuditWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void write(String actionType, String actorUsername, String actorRole, Long targetAppId, String targetAppName, Long targetUserId, Map<String,Object> detail, String result) {
        Map<String,Object> safe = new HashMap<>(detail);
        safe.remove("ssh_password_enc"); safe.remove("temporaryPassword"); safe.remove("password_hash"); safe.remove("password");
        jdbc.update("INSERT INTO audit_logs (timestamp,actor_username,actor_role,action_type,target_application_id,target_application_name,target_user_id,detail,result) VALUES (NOW(),?,?,?,?,?,?,?::jsonb,?)",
            actorUsername, actorRole, actionType, targetAppId, targetAppName, targetUserId, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(safe).toString(), result);
    }
}
