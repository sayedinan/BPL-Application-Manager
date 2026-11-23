package com.bpl.orderapp.admin.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly backup of the SQLite database using {@code VACUUM INTO}, which writes a
 * consistent copy while the application keeps running (copying the database file of
 * a WAL database directly can produce a corrupt backup). The container has no shell
 * or sqlite3 binary, so this runs inside the app.
 *
 * <p>Deliberately NOT transactional: VACUUM cannot run inside a transaction, so it
 * uses a plain autocommit connection. Backups on the same disk do not survive disk
 * or host loss; copy them off the host (see db/migration README / runbook).
 */
@Component
public class SqliteBackupService {

    private static final Logger log = LoggerFactory.getLogger(SqliteBackupService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    private static final String PREFIX = "bpl-";
    private static final String SUFFIX = ".db";

    private final DataSource dataSource;
    private final Path dir;
    private final int retentionDays;

    public SqliteBackupService(DataSource dataSource,
                               @Value("${app.backup.dir:./backups}") String dir,
                               @Value("${app.backup.retention-days:14}") int retentionDays) {
        this.dataSource = dataSource;
        this.dir = Path.of(dir).toAbsolutePath();
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${app.backup.cron:0 15 3 * * *}")
    public void nightly() {
        try {
            Path file = backupNow();
            log.info("SQLite backup written: {}", file);
        } catch (Exception e) {
            log.error("SQLite backup failed", e);
            return; // keep the older backups: do not prune after a failed run
        }
        try {
            pruneOldBackups();
        } catch (Exception e) {
            log.warn("Pruning old SQLite backups failed", e);
        }
    }

    /** Writes bpl-yyyyMMdd.db into the backup directory, replacing an earlier backup from the same day. */
    public Path backupNow() throws IOException, SQLException {
        Files.createDirectories(dir);
        Path target = dir.resolve(PREFIX + LocalDate.now().format(DAY) + SUFFIX);
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp); // VACUUM INTO refuses to write over an existing file
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, tmp.toString());
                ps.execute();
            }
            // Keep the -wal file small. Not fatal if a reader is holding it back.
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException e) {
                log.debug("wal_checkpoint(TRUNCATE) skipped: {}", e.getMessage());
            }
        }
        // Only a complete file ever has the real name.
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Deletes backup files (bpl-*.db) older than the retention period. Other files are never touched. */
    public void pruneOldBackups() throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        try (var files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)
                        && Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    Files.delete(p);
                    log.info("Deleted old SQLite backup {}", name);
                }
            }
        }
    }
}
