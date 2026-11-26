package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate G2: the baseline applies to an empty SQLite file, leaves it consistent,
 * and a second run is a no-op. Uses Flyway directly (no Spring context).
 */
class FlywayFromScratchTest {

    @TempDir
    Path dir;

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("g2.db").toAbsolutePath()
                + "?foreign_keys=on&busy_timeout=10000&journal_mode=WAL"
                + "&synchronous=NORMAL&transaction_mode=IMMEDIATE";
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url(), null, null)
                .locations("classpath:db/migration")
                .load();
    }

    @Test
    void baselineAppliesAndSecondRunIsNoOp() throws Exception {
        var first = flyway().migrate();
        assertEquals(2, first.migrationsExecuted, "V1 baseline and V2 username triggers");

        var second = flyway().migrate();
        assertEquals(0, second.migrationsExecuted, "second run must be a no-op");

        try (Connection c = DriverManager.getConnection(url());
                Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                rs.next();
                assertEquals("ok", rs.getString(1));
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rs.next(), "foreign_key_check must return no rows");
            }
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1).toLowerCase());
                }
            }
            for (String t : new String[] {
                "users", "applications", "user_application_assignments", "audit_logs",
                "application_log_lines", "idempotency_keys", "websocket_connections",
                "websocket_subscriptions", "application_status_transitions",
                "application_status_streak", "sms_send_log", "application_health_config",
                "application_health_latest", "application_health_history",
                "application_maintenance", "spring_session", "spring_session_attributes",
                "flyway_schema_history"}) {
                assertTrue(tables.contains(t), "missing table " + t);
            }
        }
    }
}
