package com.bpl.orderapp.admin.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    // Matches the unique index idx_applications_name_lower (lower(name)).
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @Query("select a.name from Application a where a.id = :id")
    Optional<String> findNameById(@Param("id") Long id);

    @Query("select a.createdAt from Application a where a.id = :id")
    Optional<Instant> findCreatedAtById(@Param("id") Long id);
}