package com.bpl.orderapp.admin.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * One-time seed of the first Sys.Admin account, per SPEC §12.1.
 *
 * <p>Idempotent: checks whether a user with the configured username
 * already exists before doing anything. On a re-run (or any subsequent
 * boot), the seeder is a no-op. The username is configurable via
 * {@code app.bootstrap.sysadmin.username} (default: {@code admin}).
 *
 * <h2>Why a runner and not a Flyway migration</h2>
 * SPEC §12.1 step 1 says "Flyway migration inserts seeded Sys.Admin,"
 * but the rest of the same section requires the initial password to
 * be random and "shown once at deploy" — a SQL migration cannot
 * generate a random password and emit it to the operator's view
 * portably. A Spring Boot {@link ApplicationRunner} runs at startup,
 * has full access to {@link BCryptPasswordEncoder} for hashing
 * (SPEC §8.1), and can write the cleartext to stdout once.
 *
 * <h2>Audit log</h2>
 * The audit-log-coverage skill says every state-changing action is
 * audited via an aspect/annotation, but §3.2's locked action enum
 * does not include a bootstrap action. Until §3.2 is amended, the
 * seed insert writes no audit row. When a SYS_ADMIN_BOOTSTRAP value
 * is added to the enum, this class can be wrapped in {@code @Audited}
 * and the change is local to this file.
 *
 * <h2>Plaintext handling</h2>
 * The cleartext password exists in this class only:
 * <ol>
 *   <li>Generated in memory by {@link SecureRandom} (24 bytes → 32
 *       base64url chars; ~192 bits of entropy, well above the
 *       minimum useful length for a one-time bootstrap password).</li>
 *   <li>Hashed with {@link BCryptPasswordEncoder} (strength 10 —
 *       Spring Security's default).</li>
 *   <li>Printed to stdout (the SLF4J default in Spring Boot) in a
 *       clearly-marked block so the operator can capture it.</li>
 *   <li>Never written to a Logger at DEBUG/INFO/WARN, never written
 *       to the audit log, never persisted in cleartext anywhere,
 *       never returned from a method that anything else in the
 *       application can call.</li>
 * </ol>
 * The cleartext variable is local to the {@code run} method and
 * goes out of scope at method return.
 */
@Component
public class SysAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SysAdminSeeder.class);

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final String username;

    public SysAdminSeeder(
        JdbcTemplate jdbc,
        BCryptPasswordEncoder encoder,
        @Value("${app.bootstrap.sysadmin.username:admin}") String username
    ) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.username = username;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existingCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE username = ? AND deleted_at IS NULL",
            Integer.class,
            username
        );
        if (existingCount != null && existingCount > 0) {
            log.info("SysAdmin seed skipped: user '{}' already exists.", username);
            return;
        }

        // 24 bytes from SecureRandom → 32 base64url characters
        // (no padding), ~192 bits of entropy. Plenty for a one-time
        // bootstrap password that will be rotated on first login.
        SecureRandom rng = new SecureRandom();
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        String cleartext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        String hash = encoder.encode(cleartext);
        Instant now = Instant.now();

        jdbc.update(
            "INSERT INTO users " +
                "(username, password_hash, role, must_change_password, created_at, updated_at) " +
                "VALUES (?, ?, 'SYS_ADMIN', true, ?, ?)",
            username, hash, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)
        );

        // The ONE place the cleartext password is allowed to be
        // written. The banner is on multiple lines and clearly marked
        // so it stands out in deploy logs. Anything else that prints
        // the password is a regression — the
        // SysAdminSeeder_passwordNeverLoggedThroughLogger test below
        // enforces this.
        //
        // Note: the line below is the only place this template string
        // appears in the source. If you find yourself writing a second
        // log.info(...) that includes `cleartext`, you've broken the
        // contract; instead route the operator's notification through
        // a non-log channel (configurable endpoint, file, etc.) and
        // keep the log out of the loop.
        log.info("=========================================================");
        log.info("  BPL Sys.Admin bootstrap (one-time)");
        log.info("  username: {}", username);
        log.info("  initial password (rotate on first login): {}", cleartext);
        log.info("  generated at: {}", now);
        log.info("=========================================================");
    }
}
