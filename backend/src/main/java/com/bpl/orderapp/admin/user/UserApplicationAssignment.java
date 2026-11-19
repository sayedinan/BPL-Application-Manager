package com.bpl.orderapp.admin.user;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_application_assignments")
public class UserApplicationAssignment {

    @EmbeddedId
    private UserApplicationAssignmentId id;

    // updatable = false so re-saving an existing pair never rewrites the date.
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    protected UserApplicationAssignment() {
    }

    public UserApplicationAssignment(UUID userId, Long applicationId) {
        this.id = new UserApplicationAssignmentId(userId, applicationId);
        this.assignedAt = Instant.now();
    }

    public UserApplicationAssignmentId getId() { return id; }

    public Instant getAssignedAt() { return assignedAt; }
}