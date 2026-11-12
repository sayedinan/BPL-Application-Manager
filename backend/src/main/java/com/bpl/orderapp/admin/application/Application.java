package com.bpl.orderapp.admin.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.net.InetAddress;
import java.time.Instant;

@Entity
@Table(name = "applications")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    // The one Postgres-specific mapping in this entity: the column type is
    // inet. For the SQLite move this becomes a String field (validated in
    // Java) plus a migration that changes the column to text.
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "server_ip", nullable = false)
    private InetAddress serverIp;

    @Column(name = "ssh_username", nullable = false, length = 64)
    private String sshUsername;

    // Encrypted by SshCredentialCipher before it gets here.
    @Column(name = "ssh_password_enc", nullable = false)
    private String sshPasswordEnc;

    @Column(name = "ssh_host_key_fingerprint", nullable = false, length = 64)
    private String sshHostKeyFingerprint;

    @Column(name = "start_script", nullable = false)
    private String startScript;

    @Column(name = "stop_script", nullable = false)
    private String stopScript;

    @Column(name = "log_script", nullable = false)
    private String logScript;

    @Column(name = "group_name", length = 60)
    private String groupName;

    @Column(name = "email_start_message")
    private String emailStartMessage;

    @Column(name = "email_stop_message")
    private String emailStopMessage;

    @Column(name = "email_external_online_message")
    private String emailExternalOnlineMessage;

    @Column(name = "email_external_offline_message")
    private String emailExternalOfflineMessage;

    @Column(name = "sms_start_message", length = 160)
    private String smsStartMessage;

    @Column(name = "sms_stop_message", length = 160)
    private String smsStopMessage;

    @Column(name = "sms_external_online_message", length = 160)
    private String smsExternalOnlineMessage;

    @Column(name = "sms_external_offline_message", length = 160)
    private String smsExternalOfflineMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public InetAddress getServerIp() { return serverIp; }
    public void setServerIp(InetAddress serverIp) { this.serverIp = serverIp; }

    public String getSshUsername() { return sshUsername; }
    public void setSshUsername(String v) { this.sshUsername = v; }

    public String getSshPasswordEnc() { return sshPasswordEnc; }
    public void setSshPasswordEnc(String v) { this.sshPasswordEnc = v; }

    public String getSshHostKeyFingerprint() { return sshHostKeyFingerprint; }
    public void setSshHostKeyFingerprint(String v) { this.sshHostKeyFingerprint = v; }

    public String getStartScript() { return startScript; }
    public void setStartScript(String v) { this.startScript = v; }

    public String getStopScript() { return stopScript; }
    public void setStopScript(String v) { this.stopScript = v; }

    public String getLogScript() { return logScript; }
    public void setLogScript(String v) { this.logScript = v; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String v) { this.groupName = v; }

    public String getEmailStartMessage() { return emailStartMessage; }
    public void setEmailStartMessage(String v) { this.emailStartMessage = v; }

    public String getEmailStopMessage() { return emailStopMessage; }
    public void setEmailStopMessage(String v) { this.emailStopMessage = v; }

    public String getEmailExternalOnlineMessage() { return emailExternalOnlineMessage; }
    public void setEmailExternalOnlineMessage(String v) { this.emailExternalOnlineMessage = v; }

    public String getEmailExternalOfflineMessage() { return emailExternalOfflineMessage; }
    public void setEmailExternalOfflineMessage(String v) { this.emailExternalOfflineMessage = v; }

    public String getSmsStartMessage() { return smsStartMessage; }
    public void setSmsStartMessage(String v) { this.smsStartMessage = v; }

    public String getSmsStopMessage() { return smsStopMessage; }
    public void setSmsStopMessage(String v) { this.smsStopMessage = v; }

    public String getSmsExternalOnlineMessage() { return smsExternalOnlineMessage; }
    public void setSmsExternalOnlineMessage(String v) { this.smsExternalOnlineMessage = v; }

    public String getSmsExternalOfflineMessage() { return smsExternalOfflineMessage; }
    public void setSmsExternalOfflineMessage(String v) { this.smsExternalOfflineMessage = v; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}