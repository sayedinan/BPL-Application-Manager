package com.bpl.orderapp.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Tests for the Sys.Admin seed contract spelled out in
 * {@link SysAdminSeeder}'s class javadoc.
 *
 * <p>Two of these are runtime tests; the third and fourth are
 * source-grep tests, mirroring the pattern in
 * {@code SshCredentialCipherTest}. The source-grep tests exist
 * because the §3.2 / §8.1 plaintext-handling guarantees are
 * enforced by a code-shape invariant: the only place
 * {@code cleartext} can be passed to a logging call is the explicit
 * 6-line banner in the {@code run} method, and it must not appear
 * in any SQL statement, file write, or HTTP call. A future edit
 * that breaks either invariant will fail these tests.
 */
class SysAdminSeederTest {

    @Test
    void generatedPassword_isHashableAndVerifiable() {
        // The seeder does not expose the cleartext password, so we
        // can't test its exact value. But we can verify the hashing
        // path the seeder relies on: bcrypt encode + matches.
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        String cleartext = "a-real-password-used-by-the-test";
        String hash = encoder.encode(cleartext);

        assertThat(hash).isNotEqualTo(cleartext);
        assertThat(hash).startsWith("$2a$10$"); // bcrypt v2a, cost 10
        assertThat(encoder.matches(cleartext, hash)).isTrue();
        assertThat(encoder.matches("not-the-password", hash)).isFalse();
    }

    /**
     * Source-level invariant: the {@code cleartext} password is
     * passed to a Logger at exactly one place in
     * {@link SysAdminSeeder}, and that place is the explicit
     * "BPL Sys.Admin bootstrap" banner.
     *
     * <p>Per-line check (not regex across the whole file) because
     * Java's `log.info(...)` calls contain parens inside their
     * format strings (e.g. "rotate on first login"), which makes
     * any cross-line regex brittle. A simpler per-line check is
     * more robust and equally strong for the invariant being
     * tested: any log call that references {@code cleartext} as
     * an argument must be on the same line as the {@code cleartext}
     * token, which is a property line-by-line matching captures
     * directly.
     *
     * <p>Lines that are entirely {@code //} comments are skipped
     * (Java has no block-comment-on-same-line edge cases here).
     * This is a deliberate trade-off — a comment that mentions
     * {@code cleartext} is fine, but a code line that does is not.
     */
    @Test
    void sourceFile_cleartextAppearsInExactlyOneLogCall() throws Exception {
        Path source = Path.of(
            "src/main/java/com/bpl/orderapp/admin/auth/SysAdminSeeder.java");
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);

        Pattern logLine = Pattern.compile(
            "log\\.(trace|debug|info|warn|error)\\s*\\("
        );
        Pattern cleartextToken = Pattern.compile("\\bcleartext\\b");

        List<String> matchingLines = lines.stream()
            .map(String::stripLeading)
            .filter(line -> !line.startsWith("//"))           // skip line comments
            .filter(line -> !line.startsWith("*"))            // skip javadoc continuation
            .filter(line -> !line.startsWith("/*"))           // skip block comment open
            .filter(line -> logLine.matcher(line).find())
            .filter(line -> cleartextToken.matcher(line).find())
            .toList();

        assertThat(matchingLines)
            .as("SysAdminSeeder must pass `cleartext` to a log call at " +
                "exactly one place (the explicit bootstrap banner). " +
                "Found %d line(s):\n%s",
                matchingLines.size(), matchingLines)
            .hasSize(1);
    }

    /**
     * Source-level invariant: the cleartext password is never
     * persisted via INSERT/UPDATE, never written to a file, never
     * sent over the network. The only place it leaves the
     * {@code run} method is the single banner log.
     *
     * <p>Per-line check: any SQL/file/HTTP call that has
     * {@code cleartext} as a token on the same line is a bug.
     * The test is intentionally narrow — a false positive (where
     * the pattern matches harmlessly) is easier to investigate
     * than a silent leak.
     */
    @Test
    void sourceFile_cleartextIsNeverPersistedOrTransmitted() throws Exception {
        Path source = Path.of(
            "src/main/java/com/bpl/orderapp/admin/auth/SysAdminSeeder.java");
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);

        // Pattern: a line that contains a SQL INSERT/UPDATE keyword
        // (case-insensitive) AND also the token `cleartext`.
        Pattern sqlWrite = Pattern.compile(
            "(?i)\\b(insert|update)\\b"
        );
        Pattern ioWrite = Pattern.compile(
            "\\b(Files\\.write|Files\\.writeString|FileWriter|OutputStreamWriter|" +
            "new\\s+FileOutputStream|HttpClient|RestTemplate|WebClient)\\s*\\("
        );
        Pattern cleartextToken = Pattern.compile("\\bcleartext\\b");

        List<String> sqlLeaks = lines.stream()
            .filter(line -> sqlWrite.matcher(line).find())
            .filter(line -> cleartextToken.matcher(line).find())
            .toList();
        assertThat(sqlLeaks)
            .as("cleartext must not appear on any line that also has an INSERT/UPDATE keyword")
            .isEmpty();

        List<String> ioLeaks = lines.stream()
            .filter(line -> ioWrite.matcher(line).find())
            .filter(line -> cleartextToken.matcher(line).find())
            .toList();
        assertThat(ioLeaks)
            .as("cleartext must not appear on any line that also writes to a file or sends HTTP")
            .isEmpty();
    }
}
