package com.bpl.orderapp.admin.status;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationStatusTransitionRepository
        extends JpaRepository<ApplicationStatusTransition, Long> {

    List<ApplicationStatusTransition> findByApplicationIdOrderByTransitionedAtAsc(Long applicationId);

    Optional<ApplicationStatusTransition> findFirstByApplicationIdAndOnlineOrderByTransitionedAtDesc(
            Long applicationId, boolean online);

    List<ApplicationStatusTransition> findByApplicationIdOrderByTransitionedAtDesc(
            Long applicationId, Pageable pageable);
}
