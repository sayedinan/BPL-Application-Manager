package com.bpl.orderapp.admin.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApplicationHealthConfigRepository
        extends JpaRepository<ApplicationHealthConfig, Long> {

    Optional<ApplicationHealthConfig> findByApplicationIdAndEnabledTrue(Long applicationId);

    boolean existsByApplicationIdAndEnabledTrue(Long applicationId);
}
