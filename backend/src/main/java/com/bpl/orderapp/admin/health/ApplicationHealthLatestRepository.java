package com.bpl.orderapp.admin.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApplicationHealthLatestRepository
        extends JpaRepository<ApplicationHealthLatest, Long> {

    // Light query: the failure counter is read on every check and the
    // payload column can be large, so don't load the whole row for it.
    @Query("select l.consecutiveFailures from ApplicationHealthLatest l where l.applicationId = :applicationId")
    Optional<Integer> findConsecutiveFailuresByApplicationId(@Param("applicationId") Long applicationId);
}
