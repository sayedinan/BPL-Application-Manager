package com.bpl.orderapp.admin.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Field-level encryption for application SSH credentials.
 *
 * <p>Backs the {@code applications.ssh_password_enc} column per SPEC §3.1
 * and §8.3. The cipher is AES-256-GCM (via Spring Security's
 * {@code Encryptors.delux}), so the on-disk ciphertext is authenticated
 * and tamper-evident — a GCM auth-tag failure surfaces as a decryption
 * error, not as silent corruption.
 *
 * <h2>Key handling</h2>
 * The master key is loaded from {@code ssh.cipher.key} (configured in
 * each profile, sourced from {@code SSH_CIPHER_KEY} in the environment).
 * The salt is a fixed application-level constant — Spring's GCM mode
 * uses a random per-encryption IV, so the salt is just a domain
 * separator, not a secret. The master key alone is what protects the
 * ciphertext.
 *
 * <h2>What this class guarantees</h2>
 * <ul>
 *   <li>Never logs the plaintext, the master key, the salt, or the
 *       ciphertext. The only thing it logs is the byte length of the
 *       input, which is safe to log and useful for capacity planning.</li>
 *   <li>Never accepts {@code null} input — both encrypt and decrypt
 *       throw {@link IllegalArgumentException} on null.</li>
 *   <li>Round-trips: {@code decrypt(encrypt(plaintext)) == plaintext}
 *       for any non-null String.</li>
 *   <li>Non-deterministic encryption: encrypting the same plaintext
 *       twice produces different ciphertexts (GCM random IV).</li>
 * </ul>
 *
 * <h2>What this class does NOT do</h2>
 * <ul>
 *   <li>It does not write to the database. That happens in the
 *       application-save service, which calls this class and persists
 *       the ciphertext. The save flow's audit row (per SPEC §3.2 and
 *       the audit-log-coverage skill) must record only the application's
 *       name, never the encrypted or decrypted password.</li>
 *   <li>It does not derive the master key. The key arrives fully formed
 *       from configuration. No KDF, no stretching — Spring Security
 *       internally derives the AES key from the password + salt using
 *       its own PBKDF2 path.</li>
 * </ul>
 *
 * <p>This is a self-contained utility: nothing in this class depends on
 * the rest of the application. It is safe to unit-test in isolation
 * with a fixed test key (see {@code SshCredentialCipherTest}).
 */
@Component
public class SshCredentialCipher {

    /**
     * Fixed application-level salt. Not a secret — Spring's GCM mode
     * uses a random per-encryption IV, so this only needs to be unique
     * to this application, not unpredictable. Hardcoding it here means
     * the same master key produces different ciphertexts on different
     * services (defense in depth, not security by obscurity).
     */
    private static final String SALT = "b0fa7cb3";

    private final TextEncryptor delegate;

    public SshCredentialCipher(@Value("${ssh.cipher.key}") String masterKey) {
        if (masterKey == null || masterKey.length() < 32) {
            throw new IllegalStateException(
                "ssh.cipher.key must be at least 32 characters (SPEC §8.3); "
                    + "got length=" + (masterKey == null ? "null" : masterKey.length()));
        }
        // Encryptors.delux uses AES-256-GCM with a random IV per
        // encryption; the ciphertext is hex-encoded. The salt and
        // master key together feed Spring's internal key derivation.
        this.delegate = Encryptors.delux(masterKey, SALT);
    }

    /**
     * Encrypts a plaintext SSH password for at-rest storage in
     * {@code applications.ssh_password_enc}.
     *
     * @param plaintext the cleartext password; must not be null
     * @return a hex-encoded AES-256-GCM ciphertext
     * @throws IllegalArgumentException if plaintext is null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        // No log line in the hot path. The class-level contract is
        // "no log line ever contains plaintext or the master key" —
        // the easiest way to honor that is to not log at all here.
        // Adding a length log would be safe (length is not content)
        // but creates a regression risk if a future edit includes
        // the input by mistake.
        String ciphertext = delegate.encrypt(plaintext);
        if (ciphertext == null) {
            // Defensive: Encryptors.delux's contract is non-null out,
            // but make the impossible visible rather than NPE later.
            // The message references no input — just the operation.
            throw new IllegalStateException("cipher returned null ciphertext");
        }
        return ciphertext;
    }

    /**
     * Decrypts a previously-encrypted SSH password for use by the
     * SSH layer (JSch connection, per SPEC §6 and §7).
     *
     * @param ciphertext the hex-encoded AES-256-GCM ciphertext
     * @return the cleartext password
     * @throws IllegalArgumentException if ciphertext is null
     * @throws org.springframework.security.crypto.InvalidKeyException
     *         if the ciphertext was tampered with (GCM auth-tag failure)
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext must not be null");
        }
        // Same rule as encrypt: no log line at all in the hot path.
        String plaintext = delegate.decrypt(ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("cipher returned null plaintext");
        }
        return plaintext;
    }
}
