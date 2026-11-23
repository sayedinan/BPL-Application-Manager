package com.bpl.orderapp.admin.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gate G4: the two native health-history queries return exact bucket and
 * slice counts on SQLite (epoch-millis checked_at), and the bundled SQLite
 * supports COUNT(*) FILTER (>= 3.30).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class HealthHistoryQueryTest {

    // Divisible by 600_000 so bucket starts line up with 10-minute buckets.
    private static final long BASE = 1_700_000_400_000L;

    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationHealthHistoryRepository repo;

    private long appId;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, "
                + "ssh_host_key_fingerprint, start_script, stop_script, log_script, created_at, updated_at) "
                + "VALUES ('hq-app','10.0.0.1','u','e','f','s','x','l',?,?)", BASE, BASE);
        appId = jdbc.queryForObject("SELECT id FROM applications WHERE name='hq-app'", Long.class);

        // before the window: must be excluded
        row(-1_000, 1, "UP", 1, 1, 1, 1);
        // bucket 0 (10 min), slice 0 (5 min): 4 checks, 1 failed (DOWN)
        row(0, 1, "UP", 10, 10, 20, 30);
        row(60_000, 1, "UP", 20, 20, 20, 30);
        row(120_000, 0, "DOWN", null, null, null, null);
        row(180_000, 1, "UP", 30, 30, 20, 30);
        // bucket 1, slice 2: 2 checks, 1 failed (status NULL)
        row(600_000, 1, "UP", 100, 50, null, null);
        row(660_000, 1, null, 200, 70, null, null);
        // bucket 2, slice 4: 1 check, DEGRADED is not a failure
        row(1_200_000, 1, "DEGRADED", 5, 5, 5, 5);
    }

    private void row(long offsetMs, int reachable, String status, Integer rt, Integer cpu,
                     Integer mem, Integer disk) {
        jdbc.update("INSERT INTO application_health_history (application_id, checked_at, reachable, "
                + "response_ms, status, cpu_percent, memory_percent, disk_percent) VALUES (?,?,?,?,?,?,?,?)",
                appId, BASE + offsetMs, reachable, rt, status, cpu, mem, disk);
    }

    @Test
    void bundledSqliteSupportsCountFilter() {
        String v = jdbc.queryForObject("select sqlite_version()", String.class);
        String[] p = v.split("\\.");
        int major = Integer.parseInt(p[0]);
        int minor = Integer.parseInt(p[1]);
        assertTrue(major > 3 || (major == 3 && minor >= 30), "SQLite " + v + " is too old for FILTER");
    }

    @Test
    void findBucketsGroupsByTenMinutes() {
        List<Object[]> rows = repo.findBuckets(appId, Instant.ofEpochMilli(BASE), 600);

        assertEquals(3, rows.size());
        long baseSec = BASE / 1000;

        Object[] b0 = rows.get(0);
        assertEquals(baseSec, ((Number) b0[0]).longValue());
        assertEquals(4, ((Number) b0[1]).longValue());
        assertEquals(1, ((Number) b0[2]).longValue());
        assertEquals(20.0, ((Number) b0[3]).doubleValue(), 1e-9);   // AVG ignores NULL: (10+20+30)/3
        assertEquals(20.0, ((Number) b0[4]).doubleValue(), 1e-9);
        assertEquals(20.0, ((Number) b0[5]).doubleValue(), 1e-9);
        assertEquals(30.0, ((Number) b0[6]).doubleValue(), 1e-9);

        Object[] b1 = rows.get(1);
        assertEquals(baseSec + 600, ((Number) b1[0]).longValue());
        assertEquals(2, ((Number) b1[1]).longValue());
        assertEquals(1, ((Number) b1[2]).longValue());
        assertEquals(150.0, ((Number) b1[3]).doubleValue(), 1e-9);
        assertEquals(60.0, ((Number) b1[4]).doubleValue(), 1e-9);
        assertNull(b1[5]);

        Object[] b2 = rows.get(2);
        assertEquals(baseSec + 1200, ((Number) b2[0]).longValue());
        assertEquals(1, ((Number) b2[1]).longValue());
        assertEquals(0, ((Number) b2[2]).longValue());
    }

    @Test
    void findUptimeSlicesCountsPerFiveMinuteSlice() {
        List<Object[]> rows = repo.findUptimeSlices(
                appId, Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + 3_600_000L),
                BASE / 1000, 300);

        assertEquals(3, rows.size());
        assertSlice(rows.get(0), 0, 4, 1);
        assertSlice(rows.get(1), 2, 2, 1);
        assertSlice(rows.get(2), 4, 1, 0);
    }

    private static void assertSlice(Object[] row, int slice, long checks, long failed) {
        assertEquals(slice, ((Number) row[0]).intValue());
        assertEquals(checks, ((Number) row[1]).longValue());
        assertEquals(failed, ((Number) row[2]).longValue());
    }
}
