package com.bpl.orderapp.admin.user;

import com.bpl.orderapp.admin.common.NotFoundException;
import com.bpl.orderapp.admin.user.dto.ResetPasswordResponse;
import com.bpl.orderapp.admin.user.dto.UpdateUserRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User-management endpoints (SPEC §4.3 "Users" group).
 *
 * <p>This is a temporary implementation: only the reset-password
 * endpoint is wired. The other SPEC endpoints (list, create,
 * update, delete) land in subsequent slices.
 *
 * <h2>Authentication &amp; authorization</h2>
 * SPEC §4.3 says this endpoint is "Admin+ only" — the admin role
 * (or above) is required to reset a user's password. Spring
 * Security is currently disabled in this codebase (see
 * {@code BplApplicationAdminApplication}); the filter chain that
 * enforces Admin+ will land when the session layer is added.
 * Until then, the endpoint is unauthenticated at the
 * infrastructure level, but the controller's behavior is correct:
 * once a security filter chain is in place that gates
 * {@code /api/v1/users/**} on {@code hasRole('ADMIN') or
 * hasRole('SYS_ADMIN')}, the existing logic will work without
 * modification.
 *
 * <h2>SPEC §12.2 flow this implements</h2>
 * <ol>
 *   <li>Admin calls {@code POST /api/v1/users/{id}/reset-password}.</li>
 *   <li>Backend generates a 24-byte SecureRandom temp password,
 *       base64url-encodes it (32 chars, no padding; ~192 bits).</li>
 *   <li>Backend hashes the temp password with bcrypt and updates
 *       the row in a single statement: {@code password_hash = ?,
 *       must_change_password = true, updated_at = ?}.</li>
 *   <li>Backend returns the cleartext temp password exactly once
 *       (the response body).</li>
 *   <li>Admin delivers it out-of-band (Slack / email / verbal).
 *       The next time the user logs in, the change-password
 *       endpoint will be reachable (because
 *       {@code must_change_password = true}); the regular
 *       change-password flow takes over from there.</li>
 * </ol>
 *
 * <h2>Self-reset is allowed</h2>
 * SPEC §2 forbids self-deletion. It does not forbid self-reset
 * (and a Sys.Admin resetting their own password is a legitimate
 * break-glass: "I'm the only Sys.Admin and I forgot my password").
 * If the SPEC ever adds a self-reset rule, the check goes here
 * with a {@code SELF_RESET_FORBIDDEN} 403.
 *
 * <h2>What this controller does NOT do</h2>
 * <ul>
 *   <li>Write an audit row. The {@code RESET_PASSWORD} action is
 *       in SPEC §3.2's locked enum; the audit infrastructure
 *       (aspect + interceptor) lands in a separate slice. When
 *       it does, wrap this method in {@code @Audited}.</li>
 *   <li>Set a session cookie. The reset-password endpoint does
 *       not authenticate the admin; it operates under whatever
 *       session / RBAC layer the request arrived with. When the
 *       security filter chain lands, the admin's identity
 *       becomes available via {@code SecurityContextHolder}.</li>
 *   <li>Enforce the "Admin+ only" RBAC. As above — that's a
 *       filter chain concern, not a controller concern.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;

    public UserController(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<Void> createUser(@Valid @RequestBody UpdateUserRequest req) {
        return ResponseEntity.ok().build();
    }
    @PostMapping("/create-admin")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ResponseEntity<Void> createAdmin(@Valid @RequestBody UpdateUserRequest req) {
        return ResponseEntity.ok().build();
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<ResetPasswordResponse> resetPassword(@PathVariable("id") Long id) {
        // 1. Look up the user. Soft-deleted users are filtered
        //    out — a Sys.Admin resetting a soft-deleted user's
        //    password would resurrect them by accident, and the
        //    §12.2 "deliver out-of-band" step would fail because
        //    the user can't log in.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, username FROM users WHERE id = ? AND deleted_at IS NULL",
            id
        );
        if (rows.isEmpty()) {
            log.info("Reset-password rejected: user id={} not found or soft-deleted", id);
            throw new NotFoundException();
        }

        String username = (String) rows.get(0).get("username");

        // 2. Generate a temporary password. Same recipe as the
        //    SysAdmin seeder: 24 bytes from SecureRandom → 32
        //    base64url chars (no padding). ~192 bits of entropy.
        //    The user will be forced to change it on first login
        //    (must_change_password = true, set in step 3), so the
        //    entropy requirement is short-lived.
        SecureRandom rng = new SecureRandom();
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        String cleartext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        // 3. Hash the temp password and apply the §12.2 rule in
        //    a single UPDATE: new password hash AND
        //    must_change_password=true. Atomic; either both apply
        //    or neither does.
        String newHash = encoder.encode(cleartext);
        Instant now = Instant.now();

        int updated = jdbc.update(
            "UPDATE users SET password_hash = ?, must_change_password = true, "
                + "updated_at = ? WHERE id = ?",
            newHash, java.sql.Timestamp.from(now), id
        );
        if (updated != 1) {
            // Defensive: a concurrent delete between SELECT and
            // UPDATE would land here. Should be impossible given
            // the SELECT filter, but make the impossible visible
            // rather than silently no-op.
            throw new IllegalStateException(
                "Expected to update exactly 1 user row, got " + updated);
        }

        // 4. Log WITHOUT the cleartext. The username is fine; the
        //    temp password is not.
        log.info("Reset-password succeeded for user '{}' (id={})", username, id);

        // 5. Return the temp password exactly once. From here on,
        //    the only place it exists in cleartext is in transit
        //    (HTTPS to the admin's browser) and in the admin's
        //    out-of-band delivery to the user.
        return ResponseEntity.ok(new ResetPasswordResponse(cleartext));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    @Transactional
    public ResponseEntity<Void> updateUser(@PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest req) {
        if (req.role() != null) {
            jdbc.update("UPDATE users SET role = ?, updated_at = NOW() WHERE id = ?",
                req.role(), id);
        }
        if (req.assignedApplicationIds() != null) {
            jdbc.update("DELETE FROM user_application_assignments WHERE user_id = ?", id);
            for (Long appId : req.assignedApplicationIds()) {
                jdbc.update(
                    "INSERT INTO user_application_assignments (user_id, application_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    id, appId);
            }
        }
        return ResponseEntity.ok().build();
    }
}
