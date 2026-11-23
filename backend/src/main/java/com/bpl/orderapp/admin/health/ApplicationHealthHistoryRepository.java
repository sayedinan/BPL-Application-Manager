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
    // The queries below are raw SQLite SQL on purpose (epoch bucketing,
    // COUNT(*) FILTER; needs SQLite >= 3.30). checked_at is stored as epoch
    // milliseconds, so the *Ms methods take longs: bind longs, never doubles,
    // so the division stays integral (it truncates, equal to floor for the
    // non-negative epoch values used here). Callers use the Instant-based
    // default methods.
    // ------------------------------------------------------------------

    /**
     * Charts: one row per time bucket.
     * Columns: [0] bucket start (epoch seconds), [1] checks, [2] failed checks,
     * [3] avg response ms, [4] avg cpu %, [5] avg memory %, [6] avg disk %.
     * Numeric columns come back as Long/Double; read them through Number.
     */
    default List<Object[]> findBuckets(Long applicationId, Instant since, int bucketSeconds) {
        return findBucketsMs(applicationId, since.toEpochMilli(), bucketSeconds * 1000L);
    }

    @Query(value = """
        SELECT (checked_at / :bucketMs) * (:bucketMs / 1000) AS bucket,
               COUNT(*) AS checks,
               COUNT(*) FILTER (WHERE NOT reachable OR status IS NULL OR status = 'DOWN') AS failed,
               AVG(response_ms) AS avg_ms,
               AVG(cpu_percent) AS avg_cpu,
               AVG(memory_percent) AS avg_memory,
               AVG(disk_percent) AS avg_disk
        FROM application_health_history
        WHERE application_id = :applicationId AND checked_at >= :sinceMs
        GROUP BY bucket ORDER BY bucket
        """, nativeQuery = true)
    List<Object[]> findBucketsMs(@Param("applicationId") Long applicationId,
                                 @Param("sinceMs") long sinceMs,
                                 @Param("bucketMs") long bucketMs);

    /**
     * Uptime bars: one row per slice inside [rangeStart, rangeEnd).
     * Columns: [0] slice index, [1] checks, [2] failed checks.
     */
    default List<Object[]> findUptimeSlices(Long applicationId, Instant rangeStart, Instant rangeEnd,
                                            long startEpoch, long sliceSeconds) {
        return findUptimeSlicesMs(applicationId, rangeStart.toEpochMilli(), rangeEnd.toEpochMilli(),
                startEpoch * 1000L, sliceSeconds * 1000L);
    }

    @Query(value = """
        SELECT CAST((checked_at - :startMs) / :sliceMs AS INTEGER) AS slice,
               COUNT(*) AS checks,
               COUNT(*) FILTER (WHERE NOT reachable OR status IS NULL OR status = 'DOWN') AS failed
        FROM application_health_history
        WHERE application_id = :applicationId AND checked_at >= :rangeStartMs AND checked_at < :rangeEndMs
        GROUP BY slice
        """, nativeQuery = true)
    List<Object[]> findUptimeSlicesMs(@Param("applicationId") Long applicationId,
                                      @Param("rangeStartMs") long rangeStartMs,
                                      @Param("rangeEndMs") long rangeEndMs,
                                      @Param("startMs") long startMs,
                                      @Param("sliceMs") long sliceMs);
}
