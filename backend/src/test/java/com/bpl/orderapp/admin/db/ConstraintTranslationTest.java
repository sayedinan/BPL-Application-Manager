package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bpl.orderapp.admin.common.IdempotencyConflictException;
import com.bpl.orderapp.admin.common.IdempotencyKeyRepository;
import com.bpl.orderapp.admin.common.IdempotencyService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The app relies on JPA translating constraint violations to Spring's
 * DataIntegrityViolationException (IdempotencyService catches it;
 * GlobalExceptionHandler maps it to 409). Hibernate's stock SQLite dialect
 * does not translate them, so BplSQLiteDialect does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class ConstraintTranslationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyKeyRepository keys;

    private UUID user;
    private long app;

    private void seed() {
        user = UUID.randomUUID();
        jdbc.update("insert into users (id, username, password_hash, role, created_at, updated_at) "
                + "values (?,?,?,?,1,1)", user.toString(), "ct-user-" + user, "h", "USER");
        jdbc.update("INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, "
                + "ssh_host_key_fingerprint, start_script, stop_script, log_script, created_at, updated_at) "
                + "VALUES (?,'10.0.0.1','u','e','f','s','x','l',1,1)", "ct-app-" + user);
        app = jdbc.queryForObject("select id from applications where name = ?", Long.class, "ct-app-" + user);
    }

    @Test
    void duplicateIdempotencyKeyIsAConflict() {
        seed();
        IdempotencyService service = new IdempotencyService(keys);
        service.checkAndStore("same-key", user, app);
        assertThrows(IdempotencyConflictException.class, () -> service.checkAndStore("same-key", user, app));
    }

    @Test
    void foreignKeyViolationIsADataIntegrityViolation() {
        seed();
        IdempotencyService service = new IdempotencyService(keys);
        // application 999999 does not exist: not a duplicate, so the violation must propagate as-is.
        assertThrows(DataIntegrityViolationException.class, () -> service.checkAndStore("k2", user, 999_999L));
    }
}
