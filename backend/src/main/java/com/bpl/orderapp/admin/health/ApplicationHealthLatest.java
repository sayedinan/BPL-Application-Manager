package com.bpl.orderapp.admin.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "application_health_latest")
public class ApplicationHealthLatest {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "reachable", nullable = false)
    private boolean reachable;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_ms")
    private Integer responseMs;

    @Column(name = "status", length = 16)
    private String status;

    // JSON text, normalized to the contract shape. TEXT column guarded by json_valid().
    @Column(name = "payload")
    private String payload;

    @Column(name = "error")
    private String error;

    @Column(name = "ssl_not_after")
    private Instant sslNotAfter;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    protected ApplicationHealthLatest() {
    }

    public ApplicationHealthLatest(Long applicationId) {
        this.applicationId = applicationId;
    }

    /**
     * Stores the outcome of one check. Same rule as the old upsert: when this
     * check has no payload or certificate expiry (it failed), the last good
     * values are kept instead of being wiped.
     */
    public void record(Instant checkedAt, boolean reachable, Integer httpStatus, Integer responseMs,
                       String status, String payload, String error, Instant sslNotAfter,
                       int consecutiveFailures) {
        this.checkedAt = checkedAt;
        this.reachable = reachable;
        this.httpStatus = httpStatus;
        this.responseMs = responseMs;
        this.status = status;
        if (payload != null) {
            this.payload = payload;
        }
        this.error = error;
        if (sslNotAfter != null) {
            this.sslNotAfter = sslNotAfter;
        }
        this.consecutiveFailures = consecutiveFailures;
    }

    public Long getApplicationId() { return applicationId; }
    public Instant getCheckedAt() { return checkedAt; }
    public boolean isReachable() { return reachable; }
    public Integer getHttpStatus() { return httpStatus; }
    public Integer getResponseMs() { return responseMs; }
    public String getStatus() { return status; }
    public String getPayload() { return payload; }
    public String getError() { return error; }
    public Instant getSslNotAfter() { return sslNotAfter; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
}
