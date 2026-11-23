package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate G1: the SQLite pragmas in the JDBC URL really apply to every pooled
 * connection. No Spring context: a bare Hikari pool built with the same URL
 * and pool settings as application-dev.yml / application-prod.yml.
 *
 * foreign_keys is per-connection in SQLite and silently defaults to OFF, so
 * this is checked on two distinct connections held at the same time.
 */
class SqlitePragmaTest {

    static final String URL_PARAMS =
            "?foreign_keys=on&busy_timeout=10000&journal_mode=WAL"
                    + "&synchronous=NORMAL&transaction_mode=IMMEDIATE";

    @TempDir
    Path dir;

    private HikariDataSource pool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dir.resolve("g1.db").toAbsolutePath() + URL_PARAMS);
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(15000);
        return new HikariDataSource(cfg);
    }

    private static String pragma(Connection c, String name) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void pragmasApplyToEveryPooledConnection() throws Exception {
        try (HikariDataSource ds = pool();
                Connection a = ds.getConnection();
                Connection b = ds.getConnection()) {
            assertNotSame(a.unwrap(java.sql.Connection.class), b.unwrap(java.sql.Connection.class));
            for (Connection c : new Connection[] {a, b}) {
                assertEquals("1", pragma(c, "foreign_keys"), "foreign_keys");
                assertEquals("wal", pragma(c, "journal_mode"), "journal_mode");
                assertEquals("10000", pragma(c, "busy_timeout"), "busy_timeout");
                assertEquals("1", pragma(c, "synchronous"), "synchronous NORMAL = 1");
            }
        }
    }

    @Test
    void foreignKeyCascadeActuallyFires() throws Exception {
        try (HikariDataSource ds = pool()) {
            try (Connection c = ds.getConnection();
                    Statement st = c.createStatement()) {
                st.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY AUTOINCREMENT)");
                st.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, "
                        + "parent_id INTEGER NOT NULL REFERENCES parent(id) ON DELETE CASCADE)");
                st.execute("INSERT INTO parent DEFAULT VALUES");
                st.execute("INSERT INTO child (id, parent_id) VALUES (1, 1)");
            }
            // Fresh connection from the pool: the cascade must still fire.
            try (Connection c = ds.getConnection();
                    Statement st = c.createStatement()) {
                st.execute("DELETE FROM parent WHERE id = 1");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM child")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1), "child row should be cascade-deleted");
                }
            }
        }
    }

    @Test
    void autoincrementNeverReusesIds() throws Exception {
        try (HikariDataSource ds = pool();
                Connection c = ds.getConnection();
                Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)");
            st.execute("INSERT INTO t (v) VALUES ('a')");
            st.execute("INSERT INTO t (v) VALUES ('b')");
            st.execute("DELETE FROM t WHERE id = 2");
            st.execute("INSERT INTO t (v) VALUES ('c')");
            try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM t")) {
                rs.next();
                assertEquals(3, rs.getInt(1), "id 2 must not be reused");
            }
        }
    }
}
