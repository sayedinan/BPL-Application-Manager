package com.bpl.orderapp.admin.maintenance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApplicationMaintenanceRepository
        extends JpaRepository<ApplicationMaintenance, Long> {

    /** The window to show in the UI: open now or scheduled for later (not yet ended). */
    Optional<ApplicationMaintenance> findByApplicationIdAndEndsAtAfter(Long applicationId, Instant now);

    /** True while the window is open: started and not yet ended. */
    @Query("select case when count(m) > 0 then true else false end from ApplicationMaintenance m "
         + "where m.applicationId = :applicationId and m.startsAt <= :now and m.endsAt > :now")
    boolean isOpen(@Param("applicationId") Long applicationId, @Param("now") Instant now);

    @Query("select m.applicationId from ApplicationMaintenance m where m.endsAt <= :now")
    List<Long> findExpiredApplicationIds(@Param("now") Instant now);

    @Query("select m.applicationId from ApplicationMaintenance m "
         + "where m.startsAt <= :now and m.startsAt > :since")
    List<Long> findApplicationIdsStartedBetween(@Param("since") Instant since, @Param("now") Instant now);

    // The two deletes return how many rows they removed. Callers act only on
    // a count above 0, so when two callers race for the same window only one
    // of them proceeds (this replaces the old DELETE ... RETURNING).
    @Modifying
    @Transactional
    @Query("delete from ApplicationMaintenance m where m.applicationId = :applicationId")
    int deleteWindow(@Param("applicationId") Long applicationId);

    @Modifying
    @Transactional
    @Query("delete from ApplicationMaintenance m "
         + "where m.applicationId = :applicationId and m.endsAt <= :now")
    int deleteWindowIfEnded(@Param("applicationId") Long applicationId, @Param("now") Instant now);
}
