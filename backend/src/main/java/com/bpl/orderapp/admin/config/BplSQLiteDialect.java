package com.bpl.orderapp.admin.config;

import java.sql.SQLException;
import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;

/**
 * Hibernate's community SQLite dialect leaves SQLite constraint and lock errors as
 * GenericJDBCException, which Spring then turns into a plain JpaSystemException.
 * The app depends on the usual translation: IdempotencyService catches
 * DataIntegrityViolationException for a duplicate key, and GlobalExceptionHandler
 * maps it to 409 DATA_CONFLICT. This maps SQLite's primary result codes to the
 * Hibernate exceptions Spring already knows how to translate:
 *
 *   SQLITE_CONSTRAINT (19, any extended code: UNIQUE, FOREIGNKEY, NOTNULL, CHECK, ...)
 *       -> ConstraintViolationException -> DataIntegrityViolationException
 *   SQLITE_BUSY (5) / SQLITE_LOCKED (6)
 *       -> LockAcquisitionException -> CannotAcquireLockException
 */
public class BplSQLiteDialect extends SQLiteDialect {

    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_CONSTRAINT = 19;

    @Override
    public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
        SQLExceptionConversionDelegate inherited = super.buildSQLExceptionConversionDelegate();
        return (SQLException e, String message, String sql) -> {
            int primary = e.getErrorCode() & 0xFF; // extended codes keep the primary code in the low byte
            if (primary == SQLITE_CONSTRAINT) {
                String constraintName = null;
                return new ConstraintViolationException(message, e, sql, constraintName);
            }
            if (primary == SQLITE_BUSY || primary == SQLITE_LOCKED) {
                return new LockAcquisitionException(message, e, sql);
            }
            return inherited == null ? null : inherited.convert(e, message, sql);
        };
    }
}
