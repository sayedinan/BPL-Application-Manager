package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.audit.AuditLogRepository;
import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.log.ApplicationLogLine;
import com.bpl.orderapp.admin.log.ApplicationLogLineRepository;
import com.bpl.orderapp.admin.status.ApplicationStatusTransition;
import com.bpl.orderapp.admin.status.ApplicationStatusTransitionRepository;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gate G5: 8 threads for 20 s against the real pool and production pragmas
 * (busy_timeout 10 s, IMMEDIATE). 3 log/status writers (as the pollers do),
 * 2 audit writers (REQUIRES_NEW), 1 user updater, 1 dashboard reader, 1 session
 * create/delete. Pass: no exception escapes, no deadlock, and the final row
 * counts equal the number of operations that reported success.
 *
 * Spring Session itself is not in the DataJpa slice, so session traffic is
 * simulated with equivalent short transactions on the SPRING_SESSION tables.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(SqliteConcurrencyTest.Config.class)
class SqliteConcurrencyTest {

    private static final long DURATION_MS = 20_000;
    private static final int MAX_RETAINED_LINES = 500;

    @TestConfiguration
    static class Config {
        @Bean
        AuditWriter auditWriter(AuditLogRepository audits, ApplicationRepository apps) {
            return new AuditWriter(audits, apps);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired AuditWriter auditWriter;
    @Autowired ApplicationLogLineRepository logs;
    @Autowired ApplicationStatusTransitionRepository transitions;
    @Autowired UserRepository users;

    private long newApp(String name) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, "
                + "ssh_host_key_fingerprint, start_script, stop_script, log_script, created_at, updated_at) "
                + "VALUES (?,'10.0.0.1','u','e','f','s','x','l',?,?)", name, now, now);
        return jdbc.queryForObject("SELECT id FROM applications WHERE name = ?", Long.class, name);
    }

    /**
     * Plan Phase 5 step 5: the log poller commits per line (select + insert + trim,
     * three commits). Measure a 500-line batch with no contention.
     */
    @Test
    void logPollerBatchOf500LinesCompletesQuickly() {
        long appId = newApp("cc-batch");
        long t0 = System.nanoTime();
        for (long n = 1; n <= 500; n++) {
            final long next = logs.findFirstByApplicationIdOrderByLineNumberDesc(appId)
                    .map(l -> l.getLineNumber() + 1).orElse(1L);
            logs.save(new ApplicationLogLine(appId, next, "line " + next, Instant.now()));
            logs.deleteUpToLine(appId, next - MAX_RETAINED_LINES);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("G5 batch: 500 log lines in " + ms + " ms");
        assertEquals(500L, jdbc.queryForObject(
                "select count(*) from application_log_lines where application_id = ?", Long.class, appId));
        assertTrue(ms < 3_000, "500 lines took " + ms + " ms; wrap each poll tick in one transaction");
    }

    @Test
    void mixedWorkloadHasNoBusyErrorsAndNoLostWrites() throws Exception {
        long[] appIds = {newApp("cc-1"), newApp("cc-2"), newApp("cc-3")};

        User u = new User();
        u.setUsername("cc-user");
        u.setPasswordHash("h");
        u.setRole("USER");
        u.setEmail("cc@example.com");
        final java.util.UUID userId = users.save(u).getId();

        int auditsBefore = jdbc.queryForObject("select count(*) from audit_logs", Integer.class);

        AtomicBoolean stop = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicLong linesOk = new AtomicLong();
        AtomicLong transitionsOk = new AtomicLong();
        AtomicLong auditsOk = new AtomicLong();
        AtomicLong userUpdatesOk = new AtomicLong();
        AtomicLong readsOk = new AtomicLong();
        AtomicLong sessionsCreated = new AtomicLong();
        AtomicLong sessionsDeleted = new AtomicLong();
        AtomicLong[] linesPerApp = {new AtomicLong(), new AtomicLong(), new AtomicLong()};

        List<Runnable> workers = new ArrayList<>();

        // 3 poller-like writers: log line (select + insert + trim, each its own transaction) + status transition
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            final long appId = appIds[i];
            workers.add(() -> {
                boolean online = false;
                while (!stop.get()) {
                    long next = logs.findFirstByApplicationIdOrderByLineNumberDesc(appId)
                            .map(l -> l.getLineNumber() + 1).orElse(1L);
                    logs.save(new ApplicationLogLine(appId, next, "line " + next, Instant.now()));
                    logs.deleteUpToLine(appId, next - MAX_RETAINED_LINES);
                    linesOk.incrementAndGet();
                    linesPerApp[idx].incrementAndGet();
                    online = !online;
                    transitions.save(new ApplicationStatusTransition(appId, online, Instant.now()));
                    transitionsOk.incrementAndGet();
                }
            });
        }
        // 2 audit writers
        for (int i = 0; i < 2; i++) {
            workers.add(() -> {
                while (!stop.get()) {
                    auditWriter.write("LOGIN", "cc", "ADMIN", null, null, null, Map.of("k", "v"), "SUCCESS");
                    auditsOk.incrementAndGet();
                }
            });
        }
        // 1 user updater (read-modify-write inside one transaction)
        workers.add(() -> {
            TransactionTemplate tx = new TransactionTemplate(txm);
            long n = 0;
            while (!stop.get()) {
                final long v = ++n;
                tx.executeWithoutResult(s -> {
                    User cur = users.findById(userId).orElseThrow();
                    cur.setFullName("Name " + v);
                    users.save(cur);
                });
                userUpdatesOk.incrementAndGet();
            }
        });
        // 1 dashboard reader
        workers.add(() -> {
            while (!stop.get()) {
                jdbc.queryForObject("select count(*) from applications", Integer.class);
                jdbc.queryForList("select id, name from applications order by name");
                users.findAssignedApplicationIds("cc-user");
                readsOk.incrementAndGet();
            }
        });
        // 1 session create/delete
        workers.add(() -> {
            TransactionTemplate tx = new TransactionTemplate(txm);
            long n = 0;
            while (!stop.get()) {
                final String id = "sess-" + (++n);
                final long now = System.currentTimeMillis();
                tx.executeWithoutResult(s -> {
                    jdbc.update("insert into SPRING_SESSION (PRIMARY_ID, SESSION_ID, CREATION_TIME, "
                            + "LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME) "
                            + "values (?,?,?,?,?,?,?)", id, id, now, now, 28800, now + 28_800_000L, "cc-user");
                    jdbc.update("insert into SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, "
                            + "ATTRIBUTE_BYTES) values (?,?,?)", id, "a", new byte[] {1, 2, 3});
                });
                sessionsCreated.incrementAndGet();
                tx.executeWithoutResult(s ->
                        jdbc.update("delete from SPRING_SESSION where PRIMARY_ID = ?", id));
                sessionsDeleted.incrementAndGet();
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(workers.size());
        long started = System.nanoTime();
        for (Runnable w : workers) {
            pool.submit(() -> {
                try {
                    w.run();
                } catch (Throwable t) {
                    failures.add(t);
                    stop.set(true); // first failure ends the run
                }
            });
        }
        Thread.sleep(DURATION_MS);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish: deadlock?");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        System.out.printf("G5 baseline: %d ms; lines=%d transitions=%d audits=%d userUpdates=%d reads=%d sessions=%d%n",
                elapsedMs, linesOk.get(), transitionsOk.get(), auditsOk.get(), userUpdatesOk.get(),
                readsOk.get(), sessionsCreated.get());

        assertTrue(failures.isEmpty(), "worker failures: " + failures);

        // No lost writes: DB counts equal operations that reported success.
        assertEquals(auditsBefore + auditsOk.get(),
                jdbc.queryForObject("select count(*) from audit_logs", Long.class));
        assertEquals(transitionsOk.get(),
                jdbc.queryForObject("select count(*) from application_status_transitions", Long.class));
        for (int i = 0; i < 3; i++) {
            long total = linesPerApp[i].get();
            long maxLine = jdbc.queryForObject(
                    "select coalesce(max(line_number),0) from application_log_lines where application_id = ?",
                    Long.class, appIds[i]);
            long kept = jdbc.queryForObject(
                    "select count(*) from application_log_lines where application_id = ?", Long.class, appIds[i]);
            assertEquals(total, maxLine, "highest line number for app " + (i + 1));
            assertEquals(Math.min(total, MAX_RETAINED_LINES), kept, "retained lines for app " + (i + 1));
        }
        assertEquals(sessionsCreated.get(), sessionsDeleted.get());
        assertEquals(0L, jdbc.queryForObject("select count(*) from SPRING_SESSION", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from SPRING_SESSION_ATTRIBUTES", Long.class),
                "session attributes must cascade-delete with their session");
        assertEquals(userUpdatesOk.get() > 0, true);
        assertEquals(readsOk.get() > 0, true);
        assertEquals("ok", jdbc.queryForObject("PRAGMA integrity_check", String.class));
    }
}
