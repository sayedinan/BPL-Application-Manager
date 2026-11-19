package com.bpl.orderapp.admin.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record UserApplicationAssignmentId(
        @Column(name = "user_id", nullable = false) UUID userId,
        @Column(name = "application_id", nullable = false) Long applicationId
) implements Serializable {
}