package com.bpl.orderapp.admin.audit;

import com.bpl.orderapp.admin.common.JsonMapConverter;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Insert-only (audit-log-coverage skill): @Immutable, getters only, no setters. */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "actor_username", nullable = false, length = 64)
    private String actorUsername;

    @Column(name = "actor_role", nullable = false, length = 16)
    private String actorRole;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(name = "target_application_id")
    private Long targetApplicationId;

    @Column(name = "target_application_name", length = 64)
    private String targetApplicationName;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "detail")
    private Map<String, Object> detail;

    @Column(nullable = false, length = 16)
    private String result;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    protected AuditLog() {}

    public AuditLog(Instant timestamp, String actorUsername, String actorRole, String actionType,
                    Long targetApplicationId, String targetApplicationName, UUID targetUserId,
                    Map<String, Object> detail, String result, String sourceIp) {
        this.timestamp = timestamp;
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
        this.actionType = actionType;
        this.targetApplicationId = targetApplicationId;
        this.targetApplicationName = targetApplicationName;
        this.targetUserId = targetUserId;
        this.detail = detail;
        this.result = result;
        this.sourceIp = sourceIp;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getActorUsername() { return actorUsername; }
    public String getActorRole() { return actorRole; }
    public String getActionType() { return actionType; }
    public Long getTargetApplicationId() { return targetApplicationId; }
    public String getTargetApplicationName() { return targetApplicationName; }
    public UUID getTargetUserId() { return targetUserId; }
    public Map<String, Object> getDetail() { return detail; }
    public String getResult() { return result; }
    public String getSourceIp() { return sourceIp; }
}