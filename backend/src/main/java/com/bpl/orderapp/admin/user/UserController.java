package com.bpl.orderapp.admin.user;

import com.bpl.orderapp.admin.audit.AuditWriter;
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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
    private final AuditWriter auditWriter;
    private final BCryptPasswordEncoder encoder;

    public UserController(JdbcTemplate jdbc, BCryptPasswordEncoder encoder, AuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.auditWriter = auditWriter;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> users = jdbc.queryForList(
            "SELECT id, username, full_name, role, email, phone_number, must_change_password, created_at FROM users WHERE deleted_at IS NULL ORDER BY username"
        );
        for (Map<String,Object> row : users) {
            java.util.UUID userRowId = (java.util.UUID) row.get("id");
            java.util.List<Long> assigned = jdbc.queryForList(
                "SELECT application_id FROM user_application_assignments WHERE user_id = ?",
                Long.class, userRowId);
            row.put("assignedApplicationIds", assigned);
            Integer liveConnections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM websocket_connections WHERE username = ?",
                Integer.class, row.get("username"));
            row.put("online", liveConnections != null && liveConnections > 0);
        }
        return ResponseEntity.ok(users);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<com.bpl.orderapp.admin.user.dto.CreateUserResponse> createUser(@Valid @RequestBody com.bpl.orderapp.admin.user.dto.CreateUserRequest req, HttpServletRequest httpRequest) {
        return doCreate(req.username(), req.fullName(), req.role(), req.email(), req.phoneNumber(), req.assignedApplicationIds(), httpRequest);
    }
    @PostMapping("/create-admin")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ResponseEntity<com.bpl.orderapp.admin.user.dto.CreateUserResponse> createAdmin(@Valid @RequestBody com.bpl.orderapp.admin.user.dto.CreateUserRequest req, HttpServletRequest httpRequest) {
        String role = req.role();
        if (!"ADMIN".equals(role) && !"SYS_ADMIN".equals(role)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "role must be ADMIN or SYS_ADMIN");
        }
        return doCreate(req.username(), req.fullName(), role, req.email(), req.phoneNumber(), req.assignedApplicationIds(), httpRequest);
    }
    private ResponseEntity<com.bpl.orderapp.admin.user.dto.CreateUserResponse> doCreate(String username, String fullName, String role, String email, String phoneNumber, java.util.List<Long> assignedIds, HttpServletRequest httpRequest) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean callerIsSysAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYS_ADMIN"));
        if (!"USER".equals(role) && !callerIsSysAdmin) {
            throw new com.bpl.orderapp.admin.common.AdminCeilingException();
        }
        java.util.List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id FROM users WHERE username = ? AND deleted_at IS NULL", username);
        if (!existing.isEmpty()) {
            throw new com.bpl.orderapp.admin.common.DuplicateNameException(username);
        }
        java.security.SecureRandom rng = new java.security.SecureRandom();
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        String cleartext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = encoder.encode(cleartext);
        java.time.Instant now = java.time.Instant.now();
        // Get the new row's id directly from the INSERT via RETURNING,
        // rather than a follow-up SELECT by username. A follow-up
        // SELECT WHERE username = ? (with no deleted_at filter) can
        // match more than one row once a username has been deleted
        // and recreated — soft-deleted rows are kept forever by
        // design — which throws IncorrectResultSizeDataAccessException
        // and previously surfaced as an uncaught 500 AFTER the insert
        // had already committed, silently losing the one-time password.
        java.util.UUID newId = jdbc.queryForObject(
            "INSERT INTO users (username, full_name, password_hash, role, email, phone_number, must_change_password, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?) RETURNING id",
            java.util.UUID.class, username, fullName.trim(), hash, role, email, phoneNumber, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        // Admin/Sys.Admin see all applications regardless of
        // assignment rows (RBAC checks role first everywhere else in
        // the app) — so assignments are only meaningful, and only
        // stored, for USER-role accounts. Silently dropping them here
        // for other roles keeps user_application_assignments free of
        // rows that would otherwise be dead data from day one.
        if (assignedIds != null && "USER".equals(role)) {
            for (Long appId : assignedIds) {
                jdbc.update(
                    "INSERT INTO user_application_assignments (user_id, application_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    newId, appId);
            }
        }
        log.info("Created user '{}' (id={}, role={})", username, newId, role);
        try {
            var currentAuth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            auditWriter.write(
                "ADMIN".equals(role) ? "CREATE_ADMIN" : "CREATE_USER",
                currentAuth.getName(),
                callerIsSysAdmin ? "SYS_ADMIN" : "ADMIN",
                null, null, newId,
                java.util.Map.of("username", username, "fullName", fullName.trim(), "role", role, "email", email,
                    "phoneNumber", phoneNumber == null ? "" : phoneNumber,
                    "assignedApplicationIds", assignedIds == null ? java.util.List.of() : assignedIds),
                "SUCCESS",
                httpRequest
            );
        } catch (Exception auditEx) {
            log.warn("Audit write failed for CREATE_USER/CREATE_ADMIN (user id={})", newId, auditEx);
        }
        return ResponseEntity.ok(new com.bpl.orderapp.admin.user.dto.CreateUserResponse(newId, username, role, cleartext));
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable java.util.UUID id, HttpServletRequest httpRequest) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean callerIsSysAdmin = auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_SYS_ADMIN"));
        List<Map<String, Object>> callerRows = jdbc.queryForList(
            "SELECT id FROM users WHERE username = ? AND deleted_at IS NULL", auth.getName());
        if (callerRows.isEmpty()) {
            throw new com.bpl.orderapp.admin.common.InvalidCredentialsException();
        }
        java.util.UUID callerId = (java.util.UUID) callerRows.get(0).get("id");
        com.bpl.orderapp.admin.accountDeletion.AccountDeletionGuard.assertCanDelete(callerId, id);
        List<Map<String, Object>> targetRows = jdbc.queryForList(
            "SELECT role FROM users WHERE id = ? AND deleted_at IS NULL", id);
        if (targetRows.isEmpty()) {
            throw new NotFoundException();
        }
        String targetRole = (String) targetRows.get(0).get("role");
        if ("SYS_ADMIN".equals(targetRole) && !callerIsSysAdmin) {
            throw new com.bpl.orderapp.admin.common.AdminCeilingException();
        }
        List<Map<String, Object>> deletedUserRows = jdbc.queryForList(
            "SELECT username FROM users WHERE id = ?", id);
        String deletedUsername = deletedUserRows.isEmpty() ? null : (String) deletedUserRows.get(0).get("username");

        jdbc.update("DELETE FROM users WHERE id = ?", id);
        if (deletedUsername != null) {
            // Kill live sessions so a deleted user's cookie can't authenticate as a
            // new user later created with the same username.
            jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", deletedUsername);
        }

        try {
            auditWriter.write(
                "DELETE_USER",
                auth.getName(),
                callerIsSysAdmin ? "SYS_ADMIN" : "ADMIN",
                null, null, id,
                java.util.Map.of("username", deletedUsername == null ? "" : deletedUsername, "role", targetRole),
                "SUCCESS",
                httpRequest
            );
        } catch (Exception auditEx) {
            log.warn("Audit write failed for DELETE_USER (user id={})", id, auditEx);
        }

        return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<ResetPasswordResponse> resetPassword(@PathVariable("id") java.util.UUID id, HttpServletRequest httpRequest) {
        var resetAuth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String resetActorRole = resetAuth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_SYS_ADMIN")) ? "SYS_ADMIN" : "ADMIN";

        // 1. Look up the user. Soft-deleted users are filtered
        //    out — a Sys.Admin resetting a soft-deleted user's
        //    password would resurrect them by accident, and the
        //    §12.2 "deliver out-of-band" step would fail because
        //    the user can't log in.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, username, role FROM users WHERE id = ? AND deleted_at IS NULL",
            id
        );
        if (rows.isEmpty()) {
            log.info("Reset-password rejected: user id={} not found or soft-deleted", id);
            throw new NotFoundException();
        }

        String username = (String) rows.get(0).get("username");

        // Admin ceiling (SPEC §1): an ADMIN must not be able to reset a
        // SYS_ADMIN's password, since they would receive the temporary
        // password and could sign in as that account. Same rule as the
        // delete and update endpoints.
        if ("SYS_ADMIN".equals(rows.get(0).get("role")) && !"SYS_ADMIN".equals(resetActorRole)) {
            throw new com.bpl.orderapp.admin.common.AdminCeilingException();
        }

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

        try {
            auditWriter.write("RESET_PASSWORD", resetAuth.getName(), resetActorRole, null, null, id,
                java.util.Map.of("username", username), "SUCCESS", httpRequest);
        } catch (Exception auditEx) {
            log.warn("Audit write failed for RESET_PASSWORD (user id={})", id, auditEx);
        }

        // 5. Return the temp password exactly once. From here on,
        //    the only place it exists in cleartext is in transit
        //    (HTTPS to the admin's browser) and in the admin's
        //    out-of-band delivery to the user.
        return ResponseEntity.ok(new ResetPasswordResponse(cleartext));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    @Transactional
    public ResponseEntity<Void> updateUser(@PathVariable java.util.UUID id,
            @Valid @RequestBody UpdateUserRequest req,
            HttpServletRequest httpRequest) {
        var updateAuth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean callerIsSysAdmin = updateAuth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_SYS_ADMIN"));

        // Snapshot BEFORE state so the audit row can show a diff,
        // not just the new values. Must happen before either the
        // role UPDATE or the assignment DELETE below.
        String oldRole = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, id);
        String oldEmail = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
        String oldPhoneNumber = jdbc.queryForObject("SELECT phone_number FROM users WHERE id = ?", String.class, id);
        String oldUsername = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, id);
        String oldFullName = jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, id);
        java.util.List<Long> oldAssignedIds = jdbc.queryForList(
            "SELECT application_id FROM user_application_assignments WHERE user_id = ?", Long.class, id);

        // Row-level guard: only Sys.Admin may edit a Sys.Admin row at
        // all (any field). Mirrors deleteUser's existing check and
        // the frontend's canManageRow visibility rule — previously
        // this was only enforced by hiding the Edit button, not by
        // the backend itself.
        if ("SYS_ADMIN".equals(oldRole) && !callerIsSysAdmin) {
            throw new com.bpl.orderapp.admin.common.AdminCeilingException();
        }

        // Username, if present, must be non-blank and not already
        // taken by another active user (mirrors doCreate's duplicate
        // check).
        if (req.username() != null) {
            if (req.username().isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "username cannot be blank");
            }
            java.util.List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM users WHERE username = ? AND deleted_at IS NULL AND id != ?",
                req.username(), id);
            if (!existing.isEmpty()) {
                throw new com.bpl.orderapp.admin.common.DuplicateNameException(req.username());
            }
        }

        // Every user must have an email (notification-module requirement).
        // Unlike the SSH password field, a present-but-blank value is
        // rejected outright rather than treated as "clear it" — @Email
        // on the DTO validates format when non-null but does not by
        // itself forbid an empty string, so that check lives here.
        if (req.email() != null && req.email().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "email cannot be blank");
        }

        if (req.fullName() != null && req.fullName().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "fullName cannot be blank");
        }
        // phoneNumber, unlike email, genuinely supports being cleared:
        // an empty string means "remove the phone number" (sets NULL
        // below), null means "leave it alone" (per hasChange()'s
        // semantics), and any other value has already passed the
        // 8801+9-digits @Pattern check on the DTO.
        if (req.phoneNumber() != null && !req.phoneNumber().isBlank() && !req.phoneNumber().matches("^8801[0-9]{9}$")) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "phoneNumber must be 8801 followed by 9 digits, or empty to clear it");
        }

        if (req.role() != null) {
            if (!"USER".equals(req.role()) && !callerIsSysAdmin) {
                throw new com.bpl.orderapp.admin.common.AdminCeilingException();
            }
            jdbc.update("UPDATE users SET role = ?, updated_at = NOW() WHERE id = ?",
                req.role(), id);
        }
        // Effective role after this update: either the new one being
        // set, or the existing one if role isn't being changed.
        String effectiveRole = req.role() != null ? req.role() : oldRole;

        if ("USER".equals(effectiveRole)) {
            if (req.assignedApplicationIds() != null) {
                jdbc.update("DELETE FROM user_application_assignments WHERE user_id = ?", id);
                for (Long appId : req.assignedApplicationIds()) {
                    jdbc.update(
                        "INSERT INTO user_application_assignments (user_id, application_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        id, appId);
                }
            }
        } else if ("USER".equals(oldRole)) {
            // Promoting away from USER: clear any assignments this
            // account had, since Admin/Sys.Admin ignore them anyway —
            // leaving them behind is exactly the stale-data case
            // flagged in the frontend review above.
            jdbc.update("DELETE FROM user_application_assignments WHERE user_id = ?", id);
        }
        if (req.phoneNumber() != null) {
            String newPhoneNumber = req.phoneNumber().isBlank() ? null : req.phoneNumber();
            jdbc.update("UPDATE users SET phone_number = ?, updated_at = NOW() WHERE id = ?",
                newPhoneNumber, id);
        }
        if (req.email() != null) {
            jdbc.update("UPDATE users SET email = ?, updated_at = NOW() WHERE id = ?",
                req.email(), id);
        }
        if (req.username() != null) {
            jdbc.update("UPDATE users SET username = ?, updated_at = NOW() WHERE id = ?", req.username(), id);
        }
        if (req.fullName() != null) {
            jdbc.update("UPDATE users SET full_name = ?, updated_at = NOW() WHERE id = ?", req.fullName().trim(), id);
        }

        try {
            java.util.Map<String, Object> detail = new java.util.HashMap<>();

            if (req.role() != null && !req.role().equals(oldRole)) {
                detail.put("oldRole", oldRole);
                detail.put("newRole", req.role());
            }

            if (req.email() != null && !req.email().equals(oldEmail)) {
                detail.put("oldEmail", oldEmail == null ? "" : oldEmail);
                detail.put("newEmail", req.email());
            }

            if (req.phoneNumber() != null && !req.phoneNumber().equals(oldPhoneNumber == null ? "" : oldPhoneNumber)) {
                detail.put("oldPhoneNumber", oldPhoneNumber == null ? "" : oldPhoneNumber);
                detail.put("newPhoneNumber", req.phoneNumber());
            }

            if (req.username() != null && !req.username().equals(oldUsername)) {
                detail.put("oldUsername", oldUsername);
                detail.put("newUsername", req.username());
            }
            if (req.fullName() != null && !req.fullName().trim().equals(oldFullName == null ? "" : oldFullName)) {
                detail.put("oldFullName", oldFullName == null ? "" : oldFullName);
                detail.put("newFullName", req.fullName().trim());
            }

            if (req.assignedApplicationIds() != null) {
                java.util.Set<Long> before = new java.util.HashSet<>(oldAssignedIds);
                java.util.Set<Long> after = new java.util.HashSet<>(req.assignedApplicationIds());

                java.util.Set<Long> addedIds = new java.util.HashSet<>(after);
                addedIds.removeAll(before);
                java.util.Set<Long> removedIds = new java.util.HashSet<>(before);
                removedIds.removeAll(after);

                if (!addedIds.isEmpty()) detail.put("assignedApplications", resolveAppNames(addedIds));
                if (!removedIds.isEmpty()) detail.put("unassignedApplications", resolveAppNames(removedIds));
            }

            // Only write a row if something actually changed. A PUT
            // with role/assignedApplicationIds identical to current
            // state (or both null) is a no-op and shouldn't clutter
            // the audit trail with an empty UPDATE_USER entry.
            if (!detail.isEmpty()) {
                auditWriter.write("UPDATE_USER", updateAuth.getName(),
                    callerIsSysAdmin ? "SYS_ADMIN" : "ADMIN",
                    null, null, id, detail, "SUCCESS", httpRequest);
            }
        } catch (Exception auditEx) {
            log.warn("Audit write failed for UPDATE_USER (user id={})", id, auditEx);
        }

        return ResponseEntity.ok().build();
    }

    /** Resolves application ids to "id:name" strings for readable audit details. */
    private java.util.List<String> resolveAppNames(java.util.Set<Long> ids) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Long appId : ids) {
            java.util.List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM applications WHERE id = ?", appId);
            String name = rows.isEmpty() ? "unknown" : (String) rows.get(0).get("name");
            result.add(appId + ":" + name);
        }
        return result;
    }
}
