package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bpl.orderapp.admin.common.SqliteLock;
import com.bpl.orderapp.admin.websocket.WebSocketConnection;
import com.bpl.orderapp.admin.websocket.WebSocketConnectionRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A real lock timeout, produced by holding the write lock on a separate raw
 * connection, must be recognised by SqliteLock whichever way it surfaces (JPA
 * transaction begin, or a plain JDBC statement). Short busy_timeout.
 */
@DataJpaTest(properties = "spring.datasource.url="
        + "jdbc:sqlite:${java.io.tmpdir}/bpl-busy-${random.uuid}.db"
        + "?foreign_keys=on&busy_timeout=200&journal_mode=WAL&synchronous=NORMAL&transaction_mode=IMMEDIATE")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SqliteBusyClassificationTest {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebSocketConnectionRepository connections;

    /** Holds the write lock on its own connection until closed. */
    private Connection holdWriteLock() throws Exception {
        String url = jdbc.queryForObject("select 1", Integer.class) == 1
                ? dataSource.getConnection().getMetaData().getURL() : null;
        Connection c = DriverManager.getConnection(url);
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("insert into websocket_connections (session_id, username, connected_at) "
                    + "values ('holder','h',1)");
        }
        return c;
    }

    @Test
    void jpaWriteWhileLockedIsClassifiedAsLockTimeout() throws Exception {
        try (Connection holder = holdWriteLock()) {
            Exception e = assertThrows(Exception.class,
                    () -> connections.save(new WebSocketConnection("s-jpa", "alice")));
            assertTrue(SqliteLock.isLockTimeout(e), "not recognised: " + e);
        }
    }

    @Test
    void jdbcWriteWhileLockedIsClassifiedAsLockTimeout() throws Exception {
        try (Connection holder = holdWriteLock()) {
            Exception e = assertThrows(Exception.class, () -> jdbc.update(
                    "insert into websocket_connections (session_id, username, connected_at) values ('s-jdbc','a',1)"));
            assertTrue(SqliteLock.isLockTimeout(e), "not recognised: " + e);
        }
    }

    @Test
    void unrelatedErrorsAreNotLockTimeouts() {
        // NOT NULL violation (username is NOT NULL) once the lock is free.
        Exception e = assertThrows(Exception.class, () -> jdbc.update(
                "insert into websocket_connections (session_id, username, connected_at) values ('x', NULL, 1)"));
        assertFalse(SqliteLock.isLockTimeout(e), "wrongly recognised: " + e);
    }
}
