package com.bpl.orderapp.admin.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface ApplicationHealthHistoryRepository
        extends JpaRepository<ApplicationHealthHistory, Long> {

    /** Nightly retention cleanup. Returns the number of rows removed. */
    @Modifying
    @Transactional
    @Query("delete from ApplicationHealthHistory h where h.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    // ------------------------------------------------------------------
    // The two queries below are PostgreSQL-specific on purpose (epoch
    // bucketing, COUNT(*) FILTER). They are the only raw SQL left for the
    // health history; rewrite them here when the database changes.
    // ------------------------------------------------------------------

    /**
     * Charts: one row per time bucket.
     * Columns: [0] bucket start (epoch seconds), [1] checks, [2] failed checks,
     * [3] avg response ms, [4] avg cpu %, [5] avg memory %, [6] avg disk %.
     */
    @Query(value = """
        SELECT floor(extract(epoch FROM checked_at) / :bucketSeconds) * :bucketSeconds AS bucket,
               COUNT(*) AS checks,
               COUNT(*) FILTER (WHERE NOT reachable OR status IS NULL OR status = 'DOWN') AS failed,
               AVG(response_ms) AS avg_ms,
               AVG(cpu_percent) AS avg_cpu,
               AVG(memory_percent) AS avg_memory,
               AVG(disk_percent) AS avg_disk
        FROM application_health_history
        WHERE application_id = :applicationId AND checked_at >= :since
        GROUP BY bucket ORDER BY bucket
        """, nativeQuery = true)
    List<Object[]> findBuckets(@Param("applicationId") Long applicationId,
                               @Param("since") Instant since,
                               @Param("bucketSeconds") int bucketSeconds);

    /**
     * Uptime bars: one row per slice inside [rangeStart, rangeEnd).
     * Columns: [0] slice index, [1] checks, [2] failed checks.
     */
    @Query(value = """
        SELECT CAST(floor((extract(epoch FROM checked_at) - :startEpoch) / :sliceSeconds) AS integer) AS slice,
               COUNT(*) AS checks,
               COUNT(*) FILTER (WHERE NOT reachable OR status IS NULL OR status = 'DOWN') AS failed
        FROM application_health_history
        WHERE application_id = :applicationId AND checked_at >= :rangeStart AND checked_at < :rangeEnd
        GROUP BY slice
        """, nativeQuery = true)
    List<Object[]> findUptimeSlices(@Param("applicationId") Long applicationId,
                                    @Param("rangeStart") Instant rangeStart,
                                    @Param("rangeEnd") Instant rangeEnd,
                                    @Param("startEpoch") long startEpoch,
                                    @Param("sliceSeconds") long sliceSeconds);
}
