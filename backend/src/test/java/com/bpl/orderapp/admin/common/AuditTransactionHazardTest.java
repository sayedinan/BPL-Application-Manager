package com.bpl.orderapp.admin.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.audit.AuditLogRepository;
import com.bpl.orderapp.admin.audit.AuditWriter;
import java.util.Map;
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
 * H1. AuditWriter is REQUIRES_NEW, which needs a second connection. SQLite has
 * one writer, so:
 *  - calling it inside an open write transaction cannot get the lock;
 *  - calling it from afterCommit is unreliable: after "commit;" the xerial
 *    driver immediately runs "begin immediate" again on the same connection
 *    (transaction_mode=IMMEDIATE) and that connection is still checked out, so it
 *    failed in testing; do not rely on it;
 *  - calling it after the transaction has completed and released its
 *    connection works. That is the rule: do the DB work in a transactional
 *    service method, audit in the caller (see UserUpdateService).
 *
 * Short busy_timeout so the hazard tests fail fast. audit_logs is insert-only
 * (trigger), so tests compare row-count deltas.
 */
@DataJpaTest(properties = "spring.datasource.url="
        + "jdbc:sqlite:${java.io.tmpdir}/bpl-audit-hazard-${random.uuid}.db"
        + "?foreign_keys=on&busy_timeout=300&journal_mode=WAL&synchronous=NORMAL&transaction_mode=IMMEDIATE")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED) // the test itself must not hold a transaction
@Import(AuditTransactionHazardTest.Config.class)
class AuditTransactionHazardTest {

    @TestConfiguration
    static class Config {
        @Bean
        AuditWriter auditWriter(AuditLogRepository audits, ApplicationRepository apps) {
            return new AuditWriter(audits, apps);
        }
    }

    @Autowired PlatformTransactionManager txm;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditWriter auditWriter;

    private int auditRows() {
        return jdbc.queryForObject("select count(*) from audit_logs", Integer.class);
    }

    private void writeSomething() {
        jdbc.update("insert into websocket_connections (session_id, username, connected_at) values (?,?,?)",
                "s-" + System.nanoTime(), "alice", 1L);
    }

    private void audit() {
        auditWriter.write("LOGIN", "alice", "ADMIN", null, null, null, Map.of(), "SUCCESS");
    }

    @Test
    void hazard_auditInsideAnOpenWriteTransactionCannotGetTheLock() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        int before = auditRows();
        assertThrows(Exception.class, () -> tx.executeWithoutResult(s -> {
            writeSomething();
            audit();
        }));
        assertEquals(before, auditRows());
    }

    @Test
    void auditAfterTheTransactionHasCompletedSucceeds() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        int before = auditRows();
        tx.executeWithoutResult(s -> writeSomething());
        audit();
        assertEquals(before + 1, auditRows());
    }

    @Test
    void auditWithNoTransactionSucceeds() {
        int before = auditRows();
        audit();
        assertEquals(before + 1, auditRows());
    }
}
