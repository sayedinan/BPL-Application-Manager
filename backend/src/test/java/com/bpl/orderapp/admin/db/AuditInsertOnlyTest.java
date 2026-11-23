package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** audit_logs is insert-only at the database level (triggers in the baseline). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AuditInsertOnlyTest {

    @Autowired JdbcTemplate jdbc;

    private long insertRow() {
        jdbc.update("insert into audit_logs (timestamp, actor_username, actor_role, action_type, result) "
                + "values (1, 'a', 'ADMIN', 'LOGIN', 'SUCCESS')");
        return jdbc.queryForObject("select max(id) from audit_logs", Long.class);
    }

    @Test
    void updateIsRejected() {
        long id = insertRow();
        Exception e = assertThrows(Exception.class,
                () -> jdbc.update("update audit_logs set actor_username = 'x' where id = ?", id));
        assertTrue(String.valueOf(e.getMessage()).contains("insert-only"), e.getMessage());
        assertEquals("a", jdbc.queryForObject("select actor_username from audit_logs where id = ?", String.class, id));
    }

    @Test
    void deleteIsRejected() {
        long id = insertRow();
        Exception e = assertThrows(Exception.class, () -> jdbc.update("delete from audit_logs where id = ?", id));
        assertTrue(String.valueOf(e.getMessage()).contains("insert-only"), e.getMessage());
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_logs where id = ?", Integer.class, id));
    }
}
