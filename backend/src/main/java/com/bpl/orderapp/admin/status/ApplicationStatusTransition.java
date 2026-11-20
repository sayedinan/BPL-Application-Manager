package com.bpl.orderapp.admin.status;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "application_status_transitions")
public class ApplicationStatusTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "online", nullable = false)
    private boolean online;

    @Column(name = "transitioned_at", nullable = false)
    private Instant transitionedAt;

    protected ApplicationStatusTransition() {
    }

    public ApplicationStatusTransition(Long applicationId, boolean online, Instant transitionedAt) {
        this.applicationId = applicationId;
        this.online = online;
        this.transitionedAt = transitionedAt;
    }

    public Long getId() { return id; }

    public Long getApplicationId() { return applicationId; }

    public boolean isOnline() { return online; }

    public Instant getTransitionedAt() { return transitionedAt; }
}
