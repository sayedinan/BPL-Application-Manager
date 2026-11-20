package com.bpl.orderapp.admin.status;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "application_status_streak")
public class ApplicationStatusStreak {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "online", nullable = false)
    private boolean online;

    @Column(name = "streak_started_at", nullable = false)
    private Instant streakStartedAt;

    protected ApplicationStatusStreak() {
    }

    public ApplicationStatusStreak(Long applicationId, boolean online, Instant streakStartedAt) {
        this.applicationId = applicationId;
        this.online = online;
        this.streakStartedAt = streakStartedAt;
    }

    public Long getApplicationId() { return applicationId; }

    public boolean isOnline() { return online; }

    public Instant getStreakStartedAt() { return streakStartedAt; }

    public void flipTo(boolean online, Instant at) {
        this.online = online;
        this.streakStartedAt = at;
    }
}
