package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bpl.orderapp.admin.application.Application;
import com.bpl.orderapp.admin.application.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Case-insensitive application names, and ids that are never reused (audit rows rely on it). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ApplicationNameUniquenessTest {

    @Autowired JdbcTemplate jdbc;

    private long insert(String name) {
        jdbc.update("INSERT INTO applications (name, server_ip, ssh_username, ssh_password_enc, "
                + "ssh_host_key_fingerprint, start_script, stop_script, log_script, created_at, updated_at) "
                + "VALUES (?,'10.0.0.1','u','e','f','s','x','l',1,1)", name);
        return jdbc.queryForObject("select id from applications where name = ?", Long.class, name);
    }

    @Autowired ApplicationRepository applications;

    private Application newApp(String name) throws Exception {
        Application a = new Application();
        a.setName(name);
        a.setServerIp(java.net.InetAddress.getByName("10.0.0.1"));
        a.setSshUsername("u");
        a.setSshPasswordEnc("e");
        a.setSshHostKeyFingerprint("f");
        a.setStartScript("s");
        a.setStopScript("x");
        a.setLogScript("l");
        return a;
    }

    @Test
    void nameDiffersOnlyByCaseIsRejectedByTheDatabase() {
        insert("Foo");
        // Plain JDBC: Spring has no SQLite error-code table, so this is not translated.
        assertThrows(org.springframework.jdbc.UncategorizedSQLException.class, () -> insert("foo"));
    }

    /**
     * What the app actually uses is JPA. GlobalExceptionHandler maps
     * DataIntegrityViolationException to 409 DATA_CONFLICT, so a constraint
     * violation coming through JPA must be translated to that type.
     */
    @Test
    void constraintViolationThroughJpaIsADataIntegrityViolation() throws Exception {
        applications.saveAndFlush(newApp("Bar"));
        assertThrows(DataIntegrityViolationException.class, () -> applications.saveAndFlush(newApp("bar")));
    }

    @Test
    void deletedIdsAreNeverReused() {
        long a = insert("id-a");
        long b = insert("id-b");
        jdbc.update("delete from applications where id = ?", b);
        long c = insert("id-c");
        assertTrue(c > b, "id " + c + " must be greater than the deleted id " + b + " (a=" + a + ")");
    }
}
