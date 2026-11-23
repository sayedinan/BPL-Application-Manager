package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bpl.orderapp.admin.config.SqliteBackupService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VACUUM INTO gives a consistent copy while the app is running (copying a WAL
 * database file directly can produce a corrupt backup). It cannot run inside a
 * transaction, so this test holds none.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(SqliteBackupService.class)
class SqliteBackupServiceTest {

    @TempDir
    static Path backups;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.backup.dir", () -> backups.toString());
        r.add("app.backup.retention-days", () -> "14");
    }

    @Autowired SqliteBackupService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() throws Exception {
        try (var files = Files.list(backups)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Files.delete(p);
            }
        }
    }

    private static String scalar(Path db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void backupIsConsistentAndContainsTheData() throws Exception {
        jdbc.update("insert into websocket_connections (session_id, username, connected_at) values ('bk-1','alice',1)");

        Path file = service.backupNow();

        assertTrue(Files.exists(file));
        assertEquals(backups.toAbsolutePath(), file.toAbsolutePath().getParent());
        assertEquals("ok", scalar(file, "PRAGMA integrity_check"));
        assertEquals("1", scalar(file, "select count(*) from websocket_connections where session_id = 'bk-1'"));
    }

    @Test
    void secondBackupOnTheSameDayReplacesTheFirst() throws Exception {
        Path first = service.backupNow();
        Path second = service.backupNow();
        assertEquals(first, second);
        try (var files = Files.list(backups)) {
            assertEquals(1, files.count(), "no temp or duplicate files left behind");
        }
    }

    @Test
    void pruneRemovesOnlyOldBackups() throws Exception {
        Path old = backups.resolve("bpl-20200101.db");
        Path recent = backups.resolve("bpl-20991231.db");
        Path other = backups.resolve("notes.txt");
        Files.writeString(old, "x");
        Files.writeString(recent, "x");
        Files.writeString(other, "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(20, ChronoUnit.DAYS)));
        Files.setLastModifiedTime(other, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        service.pruneOldBackups();

        assertFalse(Files.exists(old), "backup older than retention must be removed");
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(other), "files that are not backups are never touched");
    }
}
