package com.bpl.orderapp.admin.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface UserApplicationAssignmentRepository
        extends JpaRepository<UserApplicationAssignment, UserApplicationAssignmentId> {

    @Query("select a.id.applicationId from UserApplicationAssignment a "
         + "where a.id.userId = :userId order by a.id.applicationId")
    List<Long> findApplicationIdsByUserId(@Param("userId") UUID userId);

    // Bulk JPQL delete on purpose: it runs immediately. A derived
    // deleteBy... would queue the delete, and Hibernate runs queued inserts
    // before queued deletes, so "delete all, then re-insert the same
    // pairs" in updateUser would hit the primary key. No clearAutomatically,
    // so the User entity being edited in the same transaction stays attached.
    @Modifying
    @Transactional
    @Query("delete from UserApplicationAssignment a where a.id.userId = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}