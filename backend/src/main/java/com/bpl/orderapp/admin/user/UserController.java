package com.bpl.orderapp.admin.user;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.common.NotFoundException;
import com.bpl.orderapp.admin.user.dto.ResetPasswordResponse;
import com.bpl.orderapp.admin.user.dto.UpdateUserRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import com.bpl.orderapp.admin.websocket.WebSocketConnectionRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private final FindByIndexNameSessionRepository<?> sessionRepository;
    private final AuditWriter auditWriter;
    private final BCryptPasswordEncoder encoder;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final UserApplicationAssignmentRepository assignmentRepository;
    private final WebSocketConnectionRepository connectionRepository;
    private final UserUpdateService userUpdateService;

    public UserController(FindByIndexNameSessionRepository<?> sessionRepository, BCryptPasswordEncoder encoder, AuditWriter auditWriter,
                          UserRepository userRepository, ApplicationRepository applicationRepository,
                          UserApplicationAssignmentRepository assignmentRepository,
                          WebSocketConnectionRepository connectionRepository,
                          UserUpdateService userUpdateService) {
        this.userUpdateService = userUpdateService;
        this.connectionRepository = connectionRepository;
        this.sessionRepository = sessionRepository;
        this.encoder = encoder;
        this.auditWriter = auditWriter;
        this.userRepository = userRepository;
        this.applicationRepository = applicationRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<User> found = userRepository.findAll(org.springframework.data.domain.Sort.by("username"));
        List<Map<String, Object>> users = new java.util.ArrayList<>();
        for (User u : found) {
            // Same keys as the old SELECT so the frontend JSON doesn't change.
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("full_name", u.getFullName());
            row.put("role", u.getRole());
            row.put("email", u.getEmail());
            row.put("phone_number", u.getPhoneNumber());
            row.put("must_change_password", u.isMustChangePassword());
            row.put("created_at", u.getCreatedAt());
            java.util.List<Long> assigned = assignmentRepository.findApplicationIdsByUserId(u.getId());
            row.put("assignedApplicationIds", assigned);
            row.put("online", connectionRepository.existsByUsername(u.getUsername()));
            users.add(row);
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
        if (userRepository.existsByUsername(username)) {
            throw new com.bpl.orderapp.admin.common.DuplicateNameException(username);
        }
        java.security.SecureRandom rng = new java.security.SecureRandom();
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        String cleartext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = encoder.encode(cleartext);
        // save() generates the UUID in Java and sets created_at/updated_at
        // via the entity's @PrePersist hook, so no RETURNING is needed.
        User newUser = new User();
        newUser.setUsername(username);
        newUser.setFullName(fullName.trim());
        newUser.setPasswordHash(hash);
        newUser.setRole(role);
        newUser.setEmail(email);
        newUser.setPhoneNumber(phoneNumber);
        newUser.setMustChangePassword(true);
        java.util.UUID newId = userRepository.save(newUser).getId();
        // Admin/Sys.Admin see all applications regardless of
        // assignment rows (RBAC checks role first everywhere else in
        // the app) — so assignments are only meaningful, and only
        // stored, for USER-role accounts. Silently dropping them here
        // for other roles keeps user_application_assignments free of
        // rows that would otherwise be dead data from day one.
        if (assignedIds != null && "USER".equals(role)) {
            for (Long appId : assignedIds) {
                assignmentRepository.save(new UserApplicationAssignment(newId, appId));
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
    /** Deletes every live session whose principal is {@code username}. No-op for null. */
    private void revokeSessions(String username) {
        if (username == null) return;
        new java.util.ArrayList<>(sessionRepository.findByPrincipalName(username).keySet())
            .forEach(sessionRepository::deleteById);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYS_ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable java.util.UUID id, HttpServletRequest httpRequest) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean callerIsSysAdmin = auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_SYS_ADMIN"));
        User caller = userRepository.findByUsername(auth.getName())
            .orElseThrow(com.bpl.orderapp.admin.common.InvalidCredentialsException::new);
        java.util.UUID callerId = caller.getId();
        com.bpl.orderapp.admin.accountDeletion.AccountDeletionGuard.assertCanDelete(callerId, id);
        User target = userRepository.findById(id).orElseThrow(NotFoundException::new);
        String targetRole = target.getRole();
        if ("SYS_ADMIN".equals(targetRole) && !callerIsSysAdmin) {
            throw new com.bpl.orderapp.admin.common.AdminCeilingException();
        }
        String deletedUsername = target.getUsername();

        userRepository.deleteById(id);
        // Kill live sessions so a deleted user's cookie can't authenticate as a
        // new user later created with the same username.
        revokeSessions(deletedUsername);

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

        // 1. Look up the user through the repository.
        User user = userRepository.findById(id).orElseThrow(() -> {
            log.info("Reset-password rejected: user id={} not found", id);
            return new NotFoundException();
        });
        String username = user.getUsername();

        // Admin ceiling (SPEC §1): an ADMIN must not be able to reset a
        // SYS_ADMIN's password, since they would receive the temporary
        // password and could sign in as that account. Same rule as the
        // delete and update endpoints.
        if ("SYS_ADMIN".equals(user.getRole()) && !"SYS_ADMIN".equals(resetActorRole)) {
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
        user.setPasswordHash(newHash);
        user.setMustChangePassword(true);
        userRepository.save(user);

        // Sign the user out everywhere: a "remember me" session would otherwise keep
        // working for up to 30 days on the password that was just reset.
        revokeSessions(username);

        // Both columns still change in one UPDATE, so the "both apply or neither"
        // rule holds. updated_at comes from @PreUpdate. The updated != 1 check is
        // gone because save() has no row count, and the 404 above already covers
        // a missing user.

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
    // Deliberately NOT @Transactional. The DB work runs (and commits) inside
    // UserUpdateService.update; sessions are revoked and the audit row is written only
    // afterwards. Both use their own transaction and SQLite has one writer, so neither may
    // run inside an open transaction.
    public ResponseEntity<Void> updateUser(@PathVariable java.util.UUID id,
            @Valid @RequestBody UpdateUserRequest req,
            HttpServletRequest httpRequest) {
        var updateAuth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean callerIsSysAdmin = updateAuth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_SYS_ADMIN"));

        UserUpdateService.Result result = userUpdateService.update(id, req, callerIsSysAdmin);
        // Must run after the update has committed (see the note above).
        revokeSessions(result.usernameToRevoke());
        java.util.Map<String, Object> detail = result.auditDetail();

        try {
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
}
