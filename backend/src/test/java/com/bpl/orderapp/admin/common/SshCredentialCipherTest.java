package com.bpl.orderapp.admin.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SshCredentialCipher}.
 *
 * <p>These tests cover the contract spelled out in the class javadoc:
 * round-trip works, ciphertext is non-deterministic, tampered
 * ciphertext fails closed, null inputs are rejected, and the master
 * key length is enforced.
 *
 * <p>The "never logs plaintext" guarantee is enforced by a
 * source-grep test at the bottom of this file — the class itself
 * does not use SLF4J at all, so the test asserts that and fails the
 * build if a future edit adds a log call. The trade-off is that the
 * test is brittle to non-log edits (a no-op `Logger` import would
 * count), but a brittle test that catches a security regression is
 * better than a flexible test that doesn't.
 */
class SshCredentialCipherTest {

    /**
     * Test key, sourced from the {@code SSH_CIPHER_KEY} environment
     * variable. There is intentionally no committed default — a
     * hardcoded key value, even one tagged "test-only", would defeat
     * the "encryption key not hardcoded or committed" requirement
     * (SPEC §3.2). The Gradle test task also requires this env var
     * to be set, so a missing var fails the build at task-config
     * time rather than at test-runtime.
     */
    private static String TEST_KEY;

    @BeforeAll
    static void loadTestKey() {
        TEST_KEY = System.getenv("SSH_CIPHER_KEY");
        if (TEST_KEY == null || TEST_KEY.isEmpty()) {
            throw new IllegalStateException(
                "SSH_CIPHER_KEY is not set. Set a 32+ char test-only key in the "
                    + "environment before running tests, e.g.\n"
                    + "  SSH_CIPHER_KEY='test-only-32-char-key-set-by-CI-not-committed' "
                    + "./gradlew test");
        }
        if (TEST_KEY.length() < 32) {
            throw new IllegalStateException(
                "SSH_CIPHER_KEY must be at least 32 characters (SPEC §8.3); got length="
                    + TEST_KEY.length());
        }
    }

    @Test
    void roundTrip_returnsOriginalPlaintext() {
        SshCredentialCipher cipher = new SshCredentialCipher(TEST_KEY);
        String plaintext = "hunter2-correct-horse-battery-staple";

        String ciphertext = cipher.encrypt(plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_isNonDeterministic_soGcmIvIsActuallyRandom() {
        SshCredentialCipher cipher = new SshCredentialCipher(TEST_KEY);
        String plaintext = "same-password-encrypted-twice";

        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        // GCM uses a random IV per encryption. If two encryptions of
        // the same plaintext produced the same ciphertext, the cipher
        // would be deterministic and the random-IV property would be
        // broken (a serious security regression).
        assertThat(first).isNotEqualTo(second);

        // Both ciphertexts must still decrypt to the same plaintext.
        assertThat(cipher.decrypt(first)).isEqualTo(plaintext);
        assertThat(cipher.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        SshCredentialCipher cipher = new SshCredentialCipher(TEST_KEY);
        String ciphertext = cipher.encrypt("the-real-password");

        // Flip a character in the middle of the hex-encoded ciphertext.
        // This corrupts either the IV, the ciphertext, or the GCM
        // auth-tag. Any of the three should cause decryption to fail
        // closed, not silently produce garbage.
        char[] chars = ciphertext.toCharArray();
        int mid = chars.length / 2;
        chars[mid] = chars[mid] == 'a' ? 'b' : 'a';
        String tampered = new String(chars);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void encrypt_null_throws() {
        SshCredentialCipher cipher = new SshCredentialCipher(TEST_KEY);
        assertThatThrownBy(() -> cipher.encrypt(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decrypt_null_throws() {
        SshCredentialCipher cipher = new SshCredentialCipher(TEST_KEY);
        assertThatThrownBy(() -> cipher.decrypt(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shortMasterKey_isRejectedAtConstruction() {
        // SPEC §8.3: "Jasypt key: 32+ char random, from Vault/secrets
        // manager." A short key in production is a critical security
        // failure; the cipher should refuse to construct rather than
        // silently accept a weak key.
        assertThatThrownBy(() -> new SshCredentialCipher("too-short"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 characters");
    }

    @Test
    void nullMasterKey_isRejectedAtConstruction() {
        assertThatThrownBy(() -> new SshCredentialCipher(null))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Source-level invariant: this class must not call any logger
     * method with a value that could be the plaintext password.
     *
     * <p>Read the source file and grep for any line that mentions
     * "log" plus a method-call shape. The cipher class has no
     * business logging anything during normal operation, and a
     * future edit that adds `log.info("got password: " + plaintext)`
     * would be a security regression. The test fails the build if
     * such a line appears.
     *
     * <p>This is a deliberately strict test. It will flag
     * `private static final Logger log = ...` declarations as
     * "introduced a logger"; if you need logging, prefer a method
     * that logs only lengths or operation names, not input values.
     */
    @Test
    void sourceFile_containsNoLogCalls() throws Exception {
        Path source = Path.of(
            "src/main/java/com/bpl/orderapp/admin/common/SshCredentialCipher.java");
        String contents = Files.readString(source, StandardCharsets.UTF_8);

        // No SLF4J/Log4j/etc. logger field declarations.
        assertThat(contents)
            .as("SshCredentialCipher must not declare a logger field — logging plaintext by accident is a critical regression")
            .doesNotContain("private static final Logger")
            .doesNotContain("private static final org.slf4j.Logger")
            .doesNotContain("@Slf4j");

        // No log.* or logger.* method calls anywhere in the file.
        // The regex matches any identifier ending in `log` or starting
        // with `logger` followed by a `.` and a method call, plus
        // SLF4J's `LoggerFactory.getLogger` reference.
        Pattern logCall = Pattern.compile(
            "\\b(log|logger)\\.\\w+\\(|LoggerFactory\\.getLogger");
        assertThat(logCall.matcher(contents).find())
            .as("SshCredentialCipher must not contain log/logger method calls; matches: %s",
                contents.lines().filter(l -> logCall.matcher(l).find()).toList())
            .isFalse();
    }
}
