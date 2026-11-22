package com.bpl.orderapp.admin.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    // Matches the unique index idx_applications_name_lower (lower(name)).
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @Query("select a.name from Application a where a.id = :id")
    Optional<String> findNameById(@Param("id") Long id);

    @Query("select a.createdAt from Application a where a.id = :id")
    Optional<Instant> findCreatedAtById(@Param("id") Long id);

    @Query("select a.id from Application a")
    List<Long> findAllIds();

    // Dashboard list for ADMIN / SYS_ADMIN: every application, with its streak
    // if one exists (a just-created app has none yet and reads as offline).
    @Query("""
        select new com.bpl.orderapp.admin.application.ApplicationListRow(
            a.id, a.name, a.groupName, s.online, s.streakStartedAt)
        from Application a
        left join ApplicationStatusStreak s on s.applicationId = a.id
        order by a.name
        """)
    List<ApplicationListRow> findAllWithStatus();

    // Dashboard list for USER: only the applications assigned to them.
    @Query("""
        select new com.bpl.orderapp.admin.application.ApplicationListRow(
            a.id, a.name, a.groupName, s.online, s.streakStartedAt)
        from Application a
        join UserApplicationAssignment uaa on uaa.id.applicationId = a.id
        left join ApplicationStatusStreak s on s.applicationId = a.id
        where uaa.id.userId = :userId
        order by a.name
        """)
    List<ApplicationListRow> findAssignedWithStatus(@Param("userId") UUID userId);
}