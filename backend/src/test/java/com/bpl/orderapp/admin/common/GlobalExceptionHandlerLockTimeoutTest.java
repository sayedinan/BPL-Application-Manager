package com.bpl.orderapp.admin.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

/** SQLite lock timeouts become 503 DATABASE_BUSY (retryable), not a generic 500. */
class GlobalExceptionHandlerLockTimeoutTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static SQLException busy() {
        return new SQLException("[SQLITE_BUSY] The database file is locked (database is locked)", "SQLITE_BUSY", 5);
    }

    private void assertBusy(ResponseEntity<ProblemDetail> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().getProperties()).containsEntry("code", ErrorCode.DATABASE_BUSY);
        // No driver text in the response.
        assertThat(r.getBody().getDetail()).doesNotContain("SQLITE").doesNotContain("locked");
    }

    @Test
    void busySqlExceptionInsideJpaSystemExceptionIs503() {
        assertBusy(handler.handleDataAccess(new JpaSystemException(new jakarta.persistence.PersistenceException(busy()))));
    }

    @Test
    void springLockExceptionIs503() {
        assertBusy(handler.handleDataAccess(new CannotAcquireLockException("lock", busy())));
    }

    @Test
    void transactionBeginFailureCausedByBusyIs503() {
        assertBusy(handler.handleDataAccess(new CannotCreateTransactionException("begin failed", busy())));
    }

    @Test
    void otherDataAccessErrorsStayInternalError() {
        ResponseEntity<ProblemDetail> r = handler.handleDataAccess(
                new JpaSystemException(new jakarta.persistence.PersistenceException(new SQLException("disk I/O error", "x", 10))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }
}
