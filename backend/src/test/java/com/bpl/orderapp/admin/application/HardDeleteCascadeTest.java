package com.bpl.orderapp.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real cascade behaviour on SQLite. If PRAGMA foreign_keys were off for the
 * connection, every cascade here would silently stop working.
 * Runs in the DataJpaTest transaction, which is rolled back after each test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class HardDeleteCascadeTest {

    private static final long T = 1_700_000_000_000L;

    @Autowired JdbcTemplate jdbc;

    private long appId;
    private String userId;

    @BeforeEach
    void seed() {
        userId = UUID.randomUUID().toString();
        jdbc.update("insert into users (id, username, password_hash, role, created_at, updated_at) "
                + "values (?,?,?,?,?,?)", userId, "cascade-user", "h", "USER", T, T);
        jdbc.update("INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, "
                + "ssh_host_key_fingerprint, start_script, stop_script, log_script, created_at, updated_at) "
                + "VALUES ('cascade-app','10.0.0.1','u','e','f','s','x','l',?,?)", T, T);
        appId = jdbc.queryForObject("select id from applications where name = 'cascade-app'", Long.class);

        jdbc.update("insert into user_application_assignments (user_id, application_id, assigned_at) values (?,?,?)",
                userId, appId, T);
        jdbc.update("insert into application_log_lines (application_id, line_number, content, captured_at) "
                + "values (?,1,'x',?)", appId, T);
        jdbc.update("insert into application_health_config (application_id, url, created_at, updated_at) "
                + "values (?, 'http://h/', ?, ?)", appId, T, T);
        jdbc.update("insert into application_health_latest (application_id, checked_at, reachable) values (?,?,1)",
                appId, T);
        jdbc.update("insert into application_health_history (application_id, checked_at, reachable) values (?,?,1)",
                appId, T);
        jdbc.update("insert into application_maintenance (application_id, starts_at, ends_at, started_by, started_at) "
                + "values (?,?,?,?,?)", appId, T, T + 1000, "u", T);
        jdbc.update("insert into application_status_streak (application_id, online, streak_started_at) values (?,1,?)",
                appId, T);
        jdbc.update("insert into application_status_transitions (application_id, online, transitioned_at) values (?,1,?)",
                appId, T);
        jdbc.update("insert into idempotency_keys (idempotency_key, user_id, application_id, created_at, expires_at) "
                + "values ('k',?,?,?,?)", userId, appId, T, T + 1000);
        jdbc.update("insert into sms_send_log (application_id, recipient_phone_number, event_type, is_error, sent_at) "
                + "values (?,'8801700000000','START',0,?)", appId, T);
        jdbc.update("insert into audit_logs (timestamp, actor_username, actor_role, action_type, "
                + "target_application_id, target_user_id, result) values (?,?,?,?,?,?,?)",
                T, "a", "ADMIN", "DELETE_APPLICATION", appId, userId, "SUCCESS");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void deletingAnApplicationRemovesItsRowsButKeepsAuditAndSmsHistory() {
        jdbc.update("delete from applications where id = ?", appId);

        for (String table : new String[] {
            "user_application_assignments", "application_log_lines", "application_health_config",
            "application_health_latest", "application_health_history", "application_maintenance",
            "application_status_streak", "application_status_transitions", "idempotency_keys"}) {
            assertEquals(0, count("select count(*) from " + table + " where application_id = ?", appId),
                    table + " rows must be cascade-deleted");
        }
        // Audit rows are permanent and have no FK; the id stays as a plain value.
        assertEquals(1, count("select count(*) from audit_logs where target_application_id = ?", appId));
        // SMS history survives: its application_id is set to NULL instead of blocking the delete.
        assertEquals(1, count("select count(*) from sms_send_log"));
        assertNull(jdbc.queryForObject("select application_id from sms_send_log", Long.class));
        // The user is untouched.
        assertEquals(1, count("select count(*) from users where id = ?", userId));
    }

    @Test
    void deletingAUserRemovesAssignmentsAndIdempotencyKeysButKeepsAudit() {
        jdbc.update("delete from users where id = ?", userId);

        assertEquals(0, count("select count(*) from user_application_assignments where user_id = ?", userId));
        assertEquals(0, count("select count(*) from idempotency_keys where user_id = ?", userId));
        assertEquals(1, count("select count(*) from audit_logs where target_user_id = ?", userId));
        assertEquals(1, count("select count(*) from applications where id = ?", appId));
    }
}
