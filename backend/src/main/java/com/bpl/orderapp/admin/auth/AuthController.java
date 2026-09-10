package com.bpl.orderapp.admin.auth;

import com.bpl.orderapp.admin.auth.dto.ChangePasswordRequest;
import com.bpl.orderapp.admin.auth.dto.LoginRequest;
import com.bpl.orderapp.admin.auth.dto.LoginResponse;
import com.bpl.orderapp.admin.auth.dto.MeResponse;
import com.bpl.orderapp.admin.common.InvalidCredentialsException;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
        JdbcTemplate jdbc,
        BCryptPasswordEncoder encoder,
        SecurityContextRepository securityContextRepository
    ) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
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

        // 3. Success. Establish the server-side session:
        //    a) Build a SecurityContext with the user's identity
        //       and role as a Spring Security authority. The role
        //       string from the DB ("SYS_ADMIN"/"ADMIN"/"USER")
        //       is mapped to the conventional "ROLE_<NAME>"
        //       authority name so future hasRole() checks work
        //       without further translation.
        //    b) Push the SecurityContext into the
        //       SecurityContextHolder, then explicitly save it
        //       via the SecurityContextRepository. Spring's
        //       filter chain normally does (a) and (b)
        //       automatically via SecurityContextHolderFilter,
        //       but we're not running a security filter chain
        //       for the login endpoint yet (the chain permits
        //       /auth/login without auth). Without the explicit
        //       save, no SPRING_SESSION row is written and no
        //       SESSION cookie is set.
        //    c) The session row is then written by Spring
        //       Session's SessionRepositoryFilter on the way
        //       out (when the response is committed), and the
        //       SESSION cookie is set by the same filter via
        //       DefaultCookieSerializer. The cookie's attributes
        //       (HttpOnly, SameSite=Strict, Path=/, 8h TTL,
        //       Secure in prod) come from the spring.session.cookie
        //       block in application-prod.yml / application-dev.yml.
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            username,                // principal: the username
            null,                    // credentials: not stored in the session
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        log.info("Login succeeded for user '{}' (role={}); session established", username, role);
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

    /**
     * Current-user + assigned-application endpoint (SPEC §4.3
     * "GET /auth/me"). Used by the SPA on every page load to
     * hydrate auth state from the server-side session (per the
     * {@code frontend-auth-session-handling} skill).
     *
     * <h2>Identity source</h2>
     * The principal in the SecurityContext is the username
     * (set by the {@code login} method). The user's id, role,
     * and {@code mustChangePassword} are read fresh from the
     * {@code users} table on every call, so role or assignment
     * changes take effect on the next /auth/me hit, not on
     * the next login. The assigned application IDs come from
     * the {@code user_application_assignments} join table.
     *
     * <h2>Failure modes</h2>
     * <ul>
     *   <li>No session / no authenticated principal: 401
     *       {@code INVALID_CREDENTIALS}. The endpoint is
     *       {@code permitAll} in the filter chain (so an
     *       unauthenticated request reaches the controller
     *       rather than being rejected with a 403), and the
     *       controller returns the same envelope the login
     *       endpoint uses for credential failures. This is
     *       consistent with the SPA's expectation per the
     *       frontend-auth-session-handling skill: "If
     *       {@code GET /api/auth/me} returns 401 (no valid
     *       session), redirect to the login page."</li>
     *   <li>Authenticated principal but the user row has been
     *       deleted or soft-deleted between login and /auth/me:
     *       the username is the only thing in the SecurityContext,
     *       and a fresh SELECT finds no live row. We return 401
     *       in this case too — the session is "stale" and the
     *       SPA should re-login.</li>
     * </ul>
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me() {
        // 1. Pull the username from the SecurityContext. The
        //    login method sets the principal to the username
        //    string; if a future change moves to a UserDetails
        //    principal, this code reads the username field
        //    instead.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            // No session, or Spring Security's anonymous fallback.
            // Same envelope as a bad-credential login attempt.
            throw new InvalidCredentialsException();
        }
        String username = auth.getPrincipal().toString();

        // 2. Look up the user. Same soft-delete filter as login
        //    and change-password — a soft-deleted user with a
        //    still-valid session is treated as "no such user",
        //    and the SPA should re-login. (The 401 response
        //    above will trigger that re-login.)
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, role, must_change_password FROM users " +
                "WHERE username = ? AND deleted_at IS NULL",
            username
        );
        if (rows.isEmpty()) {
            log.info("/auth/me rejected: user '{}' not found or soft-deleted", username);
            throw new InvalidCredentialsException();
        }

        Map<String, Object> row = rows.get(0);
        Long id = ((Number) row.get("id")).longValue();
        String role = (String) row.get("role");
        boolean mustChangePassword = (Boolean) row.get("must_change_password");

        // 3. Read the assigned application IDs. The join table
        //    is filtered by the same deleted_at IS NULL on the
        //    user side (defense-in-depth: an orphaned assignment
        //    from a hard-deleted user would never match, since
        //    the user_id FK would also be gone).
        List<Long> assignedApplicationIds = jdbc.queryForList(
            "SELECT application_id FROM user_application_assignments " +
                "WHERE user_id = ? ORDER BY application_id",
            id
        ).stream()
         .map(m -> ((Number) m.get("application_id")).longValue())
         .toList();

        return ResponseEntity.ok(
            new MeResponse(id, username, role, mustChangePassword, assignedApplicationIds)
        );
    }

    /**
     * Session-invalidation endpoint (SPEC §4.3 "POST /auth/logout").
     *
     * <p>Idempotent: returns 204 No Content on success and also
     * when there is no session to invalidate. The SPA's logout
     * button can call this from a useEffect cleanup, on user
     * action, on a 401 mid-session, etc., without having to
     * reason about the "am I already logged out?" case.
     *
     * <h2>What "invalidate the session" does here</h2>
     * <ol>
     *   <li>Call {@code HttpSession.invalidate()} on the current
     *       session. With Spring Session JDBC on the classpath,
     *       this is a {@code SessionRepositoryRequestWrapper} that
     *       delegates to the underlying
     *       {@code JdbcIndexedSessionRepository} — the
     *       {@code SPRING_SESSION} and
     *       {@code SPRING_SESSION_ATTRIBUTES} rows for this
     *       session are deleted on the next commit (the response
     *       write, in this case).</li>
     *   <li>Write an explicit {@code Set-Cookie: SESSION=; Max-Age=0}
     *       to clear the cookie on the client. The
     *       {@code SpringSessionRepositoryFilter} normally
     *       writes the cookie when committing a session; if we
     *       just call {@code invalidate()} and return, the
     *       filter would not write a cookie at all (no new
     *       session to commit), and the browser would keep its
     *       existing cookie until its natural expiry. Setting
     *       {@code Max-Age=0} forces the browser to drop it
     *       immediately.</li>
     *   <li>Clear the SecurityContext so a subsequent request
     *       from the same thread (e.g. via a re-login in the
     *       same handler) doesn't see the just-invalidated
     *       principal.</li>
     * </ol>
     *
     * <h2>What this endpoint does NOT do</h2>
     * <ul>
     *   <li>Write an audit row. The {@code LOGOUT} action is
     *       in SPEC §3.2's locked enum; the audit infrastructure
     *       (aspect + interceptor) lands in a separate slice.
     *       When it does, wrap this method in {@code @Audited}.</li>
     *   <li>Invalidate OTHER sessions the same user may have
     *       open (e.g. on a second device). That is a separate
     *       "logout everywhere" feature; the SPEC's logout is
     *       scoped to the current session only.</li>
     * </ul>
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        // 1. Try to get the current session. If there is no
        //    session (e.g. the user is already logged out, or
        //    never logged in), this is the idempotent 204 path.
        //    We do NOT call session.invalidate() on a null
        //    session because that would NPE; the null check is
        //    the whole point of the idempotency guarantee.
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            log.info("Logout: invalidating session id={}", session.getId());
            // Invalidate the HttpSession. Spring Session's
            // SessionRepositoryFilter (on the way out, when the
            // response is committed) deletes the matching
            // SPRING_SESSION and SPRING_SESSION_ATTRIBUTES rows.
            session.invalidate();
        } else {
            log.info("Logout: no active session (idempotent 204)");
        }

        // 2. Clear the SecurityContextHolder so any code that
        //    reads from it in the same thread (e.g. an
        //    @Audited aspect, if one were running) doesn't see
        //    the just-invalidated principal.
        SecurityContextHolder.clearContext();

        // 3. Tell the browser to drop the SESSION cookie. The
        //    Spring Session filter would not write a Set-Cookie
        //    at all when the only session operation was an
        //    invalidate (no new session to commit), so the
        //    browser's existing cookie would persist until its
        //    natural expiry. Max-Age=0 forces immediate removal.
        //
        //    We hardcode the cookie name "SESSION" and the
        //    Path=/ + HttpOnly attributes to match what Spring
        //    Session's DefaultCookieSerializer would have sent
        //    on login. We do NOT include the Secure attribute
        //    here because (a) the dev profile uses secure=false,
        //    and (b) the browser treats Secure and non-Secure as
        //    the same cookie name, so this works for both.
        jakarta.servlet.http.Cookie clear = new jakarta.servlet.http.Cookie("SESSION", "");
        clear.setPath("/");
        clear.setHttpOnly(true);
        clear.setMaxAge(0); // 0 = delete immediately
        httpResponse.addCookie(clear);

        // 4. 204 No Content. The body is intentionally empty;
        //    there's nothing the SPA needs to read from a
        //    successful logout response.
        return ResponseEntity.noContent().build();
    }
}
