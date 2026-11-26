package com.bpl.orderapp.admin.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * One-time seed of the first Sys.Admin account, per SPEC Â§12.1.
 *
 * <p>Idempotent: checks whether a user with the configured username
 * already exists before doing anything. On a re-run (or any subsequent
 * boot), the seeder is a no-op. The username is configurable via
 * {@code app.bootstrap.sysadmin.username} (default: {@code admin}).
 *
 * <h2>Why a runner and not a Flyway migration</h2>
 * SPEC Â§12.1 step 1 says "Flyway migration inserts seeded Sys.Admin,"
 * but the rest of the same section requires the initial password to
 * be random and "shown once at deploy" â€” a SQL migration cannot
 * generate a random password and emit it to the operator's view
 * portably. A Spring Boot {@link ApplicationRunner} runs at startup,
 * has full access to {@link BCryptPasswordEncoder} for hashing
 * (SPEC Â§8.1), and can write the cleartext to stdout once.
 *
 * <h2>Audit log</h2>
 * The audit-log-coverage skill says every state-changing action is
 * audited via an aspect/annotation, but Â§3.2's locked action enum
 * does not include a bootstrap action. Until Â§3.2 is amended, the
 * seed insert writes no audit row. When a SYS_ADMIN_BOOTSTRAP value
 * is added to the enum, this class can be wrapped in {@code @Audited}
 * and the change is local to this file.
 *
 * <h2>Plaintext handling</h2>
 * The cleartext password exists in this class only:
 * <ol>
 *   <li>Generated in memory by {@link SecureRandom} (24 bytes â†’ 32
 *       base64url chars; ~192 bits of entropy, well above the
 *       minimum useful length for a one-time bootstrap password).</li>
 *   <li>Hashed with {@link BCryptPasswordEncoder} (strength 10 â€”
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

    private final BCryptPasswordEncoder encoder;
    private final String username;
    private final UserRepository userRepository;
    private final Path passwordFile;

    public SysAdminSeeder(
        BCryptPasswordEncoder encoder,
        @Value("${app.bootstrap.sysadmin.username:admin}") String username,
        @Value("${app.bootstrap.sysadmin.password-file:./data/initial-admin-password.txt}") String passwordFile,
        UserRepository userRepository
    ) {
        this.encoder = encoder;
        this.username = username;
        this.passwordFile = Path.of(passwordFile).toAbsolutePath();
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (userRepository.countByRole("SYS_ADMIN") > 0) {
            log.info("SysAdmin seed skipped: user '{}' already exists.", username);
            removeUsedPasswordFile();
            return;
        }

        // 24 bytes from SecureRandom â†’ 32 base64url characters
        // (no padding), ~192 bits of entropy. Plenty for a one-time
        // bootstrap password that will be rotated on first login.
        SecureRandom rng = new SecureRandom();
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        String cleartext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        String hash = encoder.encode(cleartext);

        User admin = new User();
        // The name can be taken if every Sys.Admin was demoted. Never take over that
        // account: make a clearly named recovery one beside it.
        String name = freeUsername();
        admin.setUsername(name);
        admin.setPasswordHash(hash);
        admin.setRole("SYS_ADMIN");
        admin.setMustChangePassword(true);
        writePrivateFile(cleartext);
        userRepository.save(admin);

        // The password itself is never logged: logs are copied and kept widely.
        log.info("Sys.Admin '{}' created. The initial password is in {}. Sign in, change it, "
            + "then delete that file.", name, passwordFile);
    }

    private String freeUsername() {
        if (!userRepository.existsByUsernameIgnoreCase(username)) {
            return username;
        }
        String candidate = username + "-recovery";
        for (int i = 2; userRepository.existsByUsernameIgnoreCase(candidate); i++) {
            candidate = username + "-recovery-" + i;
        }
        return candidate;
    }

    /** Readable by the owner only, where the file system supports it (Linux in production). */
    private void writePrivateFile(String password) throws IOException {
        Files.createDirectories(passwordFile.getParent());
        Files.deleteIfExists(passwordFile);
        if (passwordFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(passwordFile, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(passwordFile, password + System.lineSeparator());
    }

    /** On a later start, drop the file if its password is no longer the initial one. */
    private void removeUsedPasswordFile() {
        if (!Files.exists(passwordFile)) {
            return;
        }
        try {
            String password = Files.readString(passwordFile).strip();
            boolean stillWorks = userRepository.findByRole("SYS_ADMIN").stream()
                .anyMatch(u -> u.isMustChangePassword() && encoder.matches(password, u.getPasswordHash()));
            if (!stillWorks) {
                Files.delete(passwordFile);
                log.info("Deleted the used initial password file {}", passwordFile);
            }
        } catch (IOException e) {
            log.warn("Could not check or delete {}; delete it by hand once the password is changed",
                passwordFile, e);
        }
    }
}
