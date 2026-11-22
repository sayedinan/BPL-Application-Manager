package com.bpl.orderapp.admin.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "application_health_config")
public class ApplicationHealthConfig {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "api_key_enc")
    private String apiKeyEnc;

    @Column(name = "tls_pin_sha256", length = 64)
    private String tlsPinSha256;

    @Column(name = "poll_interval_seconds", nullable = false)
    private int pollIntervalSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApplicationHealthConfig() {
    }

    public ApplicationHealthConfig(Long applicationId) {
        this.applicationId = applicationId;
    }

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

    /** Everything except the API key, which is only touched when the caller sends one. */
    public void apply(boolean enabled, String url, String tlsPinSha256, int pollIntervalSeconds) {
        this.enabled = enabled;
        this.url = url;
        this.tlsPinSha256 = tlsPinSha256;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public Long getApplicationId() { return applicationId; }
    public boolean isEnabled() { return enabled; }
    public String getUrl() { return url; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public String getTlsPinSha256() { return tlsPinSha256; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
}
