package com.bpl.orderapp.admin.auth;

import com.bpl.orderapp.admin.auth.dto.ChangePasswordRequest;
import com.bpl.orderapp.admin.auth.dto.LoginRequest;
import com.bpl.orderapp.admin.auth.dto.LoginResponse;
import com.bpl.orderapp.admin.common.InvalidCredentialsException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Auth endpoints (SPEC §4.3 "Auth" group).
 *
 * <p>This is a temporary implementation: only the credential check
 * portion of {@code POST /auth/login} and the forced
 * change-password flow of {@code POST /auth/change-password} are
 * wired. The full specification calls for the login endpoint to set
 * an HttpOnly session cookie on success — that lands when the
 * session layer is added.
 *
 * <h2>Security invariants in this slice</h2>
 * <ul>
 *   <li><b>Generic 401.</b> "User not found" and "wrong password"
 *       both return the same {@code INVALID_CREDENTIALS} response.
 *       Distinguishing them would let an attacker enumerate valid
 *       usernames by probing the endpoint.</li>
 *   <li><b>Constant-time password comparison.</b> The bcrypt
 *       {@code matches} call is the timing-sensitive path; the
 *       username lookup is gated by an early return that does
 *       NOT short-circuit on missing users. The {@code matches}
 *       call is invoked against a fixed dummy hash when the
 *       user does not exist, so the response timing for
 *       "no such user" and "wrong password" is indistinguishable.</li>
 *   <li><b>Cleartext password is never logged or persisted.</b>
 *       The {@code password} field of {@link LoginRequest} is used
 *       exactly once (the {@code matches} call) and goes out of
 *       scope at the end of the request. The request body itself
 *       is not bound to any field that survives the controller
 *       method. No {@code toString()} on the request, no logging
 *       of the request, no audit row written by this endpoint
 *       (audit for LOGIN lands with the audit-log-coverage skill
 *       and a future §3.2 amendment).</li>
 *   <li><b>No 403-vs-401 distinction.</b> The endpoint does not
 *       need to check the role or {@code mustChangePassword}
 *       beyond what the response carries — the frontend reads
 *       {@code mustChangePassword} and routes accordingly. The
 *       server still allows any role to authenticate; the
 *       forced-change redirect is a client-side concern, but
 *       the backend's session-based enforcement (in the
 *       upcoming session layer) will mirror it.</li>
 * </ul>
 *
 * <h2>Why a controller, not a service</h2>
 * The credential check lives in the controller for now because
 * there is no {@code UserService} yet. When user-management
 * service lands, the JdbcTemplate call moves there; the
 * controller then calls the service and translates the result.
 * The DTOs (LoginRequest, LoginResponse) stay in this package
 * either way.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * Pre-computed bcrypt hash of a value that is never used. The
     * purpose of this hash is to make "user does not exist" take
     * the same time as "user exists, wrong password" by routing
     * both through {@code BCryptPasswordEncoder.matches} with a
     * real bcrypt-shaped string.
     *
     * <p>The plaintext that hashes to this string is irrelevant
     * — the hash just needs to be a syntactically valid bcrypt
     * hash so the matches() call doesn't fail with a
     * "not a bcrypt hash" exception. The cleartext used to
     * produce this hash is not stored anywhere.
     */
    private static final String DUMMY_BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;

    public AuthController(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 1. Look up the user. Soft-deleted users are filtered out —
        //    a soft-deleted user with a matching username should NOT
        //    be able to authenticate, even if their password hash
        //    is still in the table.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, username, role, password_hash, must_change_password " +
                "FROM users " +
                "WHERE username = ? AND deleted_at IS NULL",
            request.username()
        );

        String passwordHash;
        Long id;
        String username;
        String role;
        boolean mustChangePassword;

        if (rows.isEmpty()) {
            // No such user (or soft-deleted). To avoid leaking
            // which usernames exist, run a real bcrypt comparison
            // against a dummy hash so the timing matches the
            // "user exists, wrong password" path.
            log.info("Login attempt for unknown or soft-deleted user: '{}'", request.username());
            encoder.matches(request.password(), DUMMY_BCRYPT_HASH);
            throw new InvalidCredentialsException();
        }

        Map<String, Object> row = rows.get(0);
        id = ((Number) row.get("id")).longValue();
        username = (String) row.get("username");
        role = (String) row.get("role");
        passwordHash = (String) row.get("password_hash");
        mustChangePassword = (Boolean) row.get("must_change_password");

        // 2. Constant-time password check. BCryptPasswordEncoder.matches
        //    uses the underlying bcrypt constant-time compare.
        if (!encoder.matches(request.password(), passwordHash)) {
            log.info("Login failed: invalid password for user '{}'", username);
            throw new InvalidCredentialsException();
        }

        // 3. Success. Response carries id, username, role, and the
        //    mustChangePassword flag so the frontend can route.
        //    No cookie / no session yet — that arrives with the
        //    session layer.
        log.info("Login succeeded for user '{}' (role={})", username, role);
        return ResponseEntity.ok(
            new LoginResponse(id, username, role, mustChangePassword)
        );
    }

    /**
     * Forced change-password flow (SPEC §4.3, §8.1).
     *
     * <p>The endpoint accepts the username, the current password,
     * and the new password. It re-validates the current password
     * (defeating body-replay / CSRF on a no-session endpoint),
     * rejects too-short or same-as-old new passwords, hashes the
     * new password with bcrypt, and clears the
     * {@code must_change_password} flag in a single SQL UPDATE.
     *
     * <h2>Server-side vs client-side enforcement</h2>
     * The SPEC's "block navigation to anything else until changed"
     * rule is fundamentally a frontend concern: the SPA renders
     * the {@code /change-password} page, and any other route
     * redirect to it while {@code mustChangePassword=true}. The
     * backend's only role here is to flip the flag and re-emit
     * the user state so the SPA can drop its lock without a
     * second round-trip. Once sessions land, server-side route
     * enforcement is straightforward — until then, the SPA is
     * the only enforcement layer.
     *
     * <h2>What this method does NOT do</h2>
     * <ul>
     *   <li>Set a session cookie. The endpoint returns the user
     *       state (id, username, role, mustChangePassword=false)
     *       so the SPA can re-render, but no auth ticket is
     *       issued. When the session layer lands, this method
     *       will additionally establish the session here.</li>
     *   <li>Write an audit row. The audit-log-coverage skill
     *       requires a {@code CHANGE_PASSWORD} audit entry; the
     *       action is in SPEC §3.2's locked enum, but no aspect
     *       infrastructure exists yet. When the audit infrastructure
     *       lands, wrap this method in {@code @Audited}.</li>
     * </ul>
     */
    @PostMapping("/change-password")
    public ResponseEntity<LoginResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {

        // 1. Look up the user. Same soft-delete filter as login —
        //    a soft-deleted user must not be able to change their
        //    password (and they should not be able to authenticate
        //    either, per login's filter).
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, username, role, password_hash FROM users " +
                "WHERE username = ? AND deleted_at IS NULL",
            request.username()
        );

        if (rows.isEmpty()) {
            log.info("Change-password attempt for unknown or soft-deleted user: '{}'",
                request.username());
            // Constant-time: run a real bcrypt matches against the
            // dummy hash so timing matches the "user exists, wrong
            // old password" path.
            encoder.matches(request.oldPassword(), DUMMY_BCRYPT_HASH);
            throw new InvalidCredentialsException();
        }

        Map<String, Object> row = rows.get(0);
        Long id = ((Number) row.get("id")).longValue();
        String username = (String) row.get("username");
        String role = (String) row.get("role");
        String currentHash = (String) row.get("password_hash");

        // 2. Re-validate the current password. This is the
        //    no-session identity check; the SPA also enforces it
        //    (mustChangePassword routes to here, and the user
        //    presumably knows their current password if they're
        //    the legitimate user).
        if (!encoder.matches(request.oldPassword(), currentHash)) {
            log.info("Change-password rejected: wrong current password for user '{}'",
                username);
            throw new InvalidCredentialsException();
        }

        // 3. Reject new password = old password. This is a UX
        //    guard, not a security one (the hash is bcrypt, so
        //    there's no plaintext equality to expose). Bean
        //    Validation already enforced min/max length.
        if (request.newPassword().equals(request.oldPassword())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "New password must differ from the current password"
            );
        }

        // 4. Hash the new password and update the row in one
        //    statement. The trigger that updates `updated_at` is
        //    not installed (V2 deliberately did not include it
        //    per the SPEC), so we set it explicitly. The
        //    `must_change_password` flip and the new hash are
        //    applied atomically.
        String newHash = encoder.encode(request.newPassword());
        Instant now = Instant.now();

        int updated = jdbc.update(
            "UPDATE users SET password_hash = ?, must_change_password = false, "
                + "updated_at = ? WHERE id = ?",
            newHash, java.sql.Timestamp.from(now), id
        );
        if (updated != 1) {
            // Defensive: should be impossible given the SELECT
            // above, but a concurrent delete would land here.
            throw new IllegalStateException(
                "Expected to update exactly 1 user row, got " + updated);
        }

        log.info("Change-password succeeded for user '{}'", username);

        // 5. Return the same shape as /auth/login so the SPA can
        //    replace its in-memory user state with one round-trip.
        //    mustChangePassword is now false, so the SPA's route
        //    guard will let the user navigate away from
        //    /change-password.
        return ResponseEntity.ok(
            new LoginResponse(id, username, role, false)
        );
    }
}
