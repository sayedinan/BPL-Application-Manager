package com.bpl.orderapp.admin.status;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationStatusStreakRepository
        extends JpaRepository<ApplicationStatusStreak, Long> {

    /** Last-known state; an application with no streak row yet reads as offline. */
    default boolean isOnline(Long applicationId) {
        return findById(applicationId).map(ApplicationStatusStreak::isOnline).orElse(false);
    }
}
