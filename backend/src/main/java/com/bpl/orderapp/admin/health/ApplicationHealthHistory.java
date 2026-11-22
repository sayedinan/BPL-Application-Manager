package com.bpl.orderapp.admin.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "application_health_history")
public class ApplicationHealthHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "reachable", nullable = false)
    private boolean reachable;

    @Column(name = "response_ms")
    private Integer responseMs;

    @Column(name = "status", length = 16)
    private String status;

    // REAL columns in the database, so Float here.
    @Column(name = "cpu_percent")
    private Float cpuPercent;

    @Column(name = "memory_percent")
    private Float memoryPercent;

    @Column(name = "disk_percent")
    private Float diskPercent;

    protected ApplicationHealthHistory() {
    }

    public ApplicationHealthHistory(Long applicationId, Instant checkedAt, boolean reachable,
                                    Integer responseMs, String status,
                                    Double cpuPercent, Double memoryPercent, Double diskPercent) {
        this.applicationId = applicationId;
        this.checkedAt = checkedAt;
        this.reachable = reachable;
        this.responseMs = responseMs;
        this.status = status;
        this.cpuPercent = cpuPercent == null ? null : cpuPercent.floatValue();
        this.memoryPercent = memoryPercent == null ? null : memoryPercent.floatValue();
        this.diskPercent = diskPercent == null ? null : diskPercent.floatValue();
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public Instant getCheckedAt() { return checkedAt; }
    public boolean isReachable() { return reachable; }
    public Integer getResponseMs() { return responseMs; }
    public String getStatus() { return status; }
    public Float getCpuPercent() { return cpuPercent; }
    public Float getMemoryPercent() { return memoryPercent; }
    public Float getDiskPercent() { return diskPercent; }
}
