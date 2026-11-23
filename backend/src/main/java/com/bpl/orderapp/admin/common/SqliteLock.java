package com.bpl.orderapp.admin.common;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Recognises a SQLite lock timeout (SQLITE_BUSY / SQLITE_LOCKED) however it is
 * wrapped: Spring's lock exceptions, Hibernate/JPA wrappers, or a transaction
 * that failed to begin. Walks the cause chain; no compile dependency on the
 * driver (it is a runtime-only dependency).
 */
public final class SqliteLock {

    private static final int MAX_DEPTH = 12;
    // Primary SQLite result codes (extended codes carry these in the low byte).
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;

    private SqliteLock() {}

    public static boolean isLockTimeout(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof CannotAcquireLockException || t instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                int primary = sql.getErrorCode() & 0xFF;
                if (primary == SQLITE_BUSY || primary == SQLITE_LOCKED) {
                    return true;
                }
                String msg = sql.getMessage();
                if (msg != null && (msg.contains("SQLITE_BUSY") || msg.contains("SQLITE_LOCKED")
                        || msg.contains("database is locked"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
