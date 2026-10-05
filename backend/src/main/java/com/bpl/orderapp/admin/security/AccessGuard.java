package com.bpl.orderapp.admin.security;

import com.bpl.orderapp.admin.common.AccessDeniedAppException;
import com.bpl.orderapp.admin.common.InvalidCredentialsException;
import com.bpl.orderapp.admin.common.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces {@link Rbac} inside controllers: works out who the caller is
 * from the session, then asks Rbac whether they may do the action (and,
 * for per-application actions, whether they may touch that application).
 *
 * <p>Rbac stays the single source of truth for every decision. This class
 * only fetches the two facts Rbac needs and cannot get itself: the
 * caller's role, and, for a USER, the applications assigned to them. It
 * contains no permission rules of its own.
 *
 * <p>A failed check throws {@link AccessDeniedAppException}, which
 * GlobalExceptionHandler turns into a 403. A missing or unknown caller
 * throws {@link InvalidCredentialsException}.
 *
 * <p>Call it as the first line of an endpoint, before any work or any
 * state-dependent check, so a caller without permission learns nothing
 * about the application's state:
 * <pre>
 *   accessGuard.require(Action.CREATE_APPLICATION);
 *   accessGuard.requireApplication(Action.CONTROL_APPLICATION, id);
 * </pre>
 */
@Component
public class AccessGuard {

    /** The authenticated caller, as the database knows them. */
    public record Caller(UUID userId, String username, Role role) {}

    private final JdbcTemplate jdbc;

    public AccessGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Caller currentCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new InvalidCredentialsException();
        }
        String username = auth.getName();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, role FROM users WHERE username = ? AND deleted_at IS NULL", username);
        if (rows.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        return new Caller((UUID) rows.get(0).get("id"), username, Role.valueOf((String) rows.get(0).get("role")));
    }

    /** Coarse gate: may this caller's role perform the action at all? */
    public Caller require(Action action) {
        Caller caller = currentCaller();
        if (!Rbac.canAccess(caller.role(), action)) {
            throw new AccessDeniedAppException();
        }
        return caller;
    }

    /**
     * Coarse gate plus the per-application gate: SYS_ADMIN and ADMIN may
     * touch any application, a USER only the ones assigned to them.
     */
    public Caller requireApplication(Action action, Long applicationId) {
        Caller caller = require(action);
        List<Long> assigned = caller.role() == Role.USER
            ? jdbc.queryForList(
                "SELECT uaa.application_id FROM user_application_assignments uaa "
                    + "JOIN users u ON u.id = uaa.user_id "
                    + "WHERE u.username = ? AND u.deleted_at IS NULL",
                Long.class, caller.username())
            : List.of();
        if (!Rbac.canAccessApplication(caller.role(), assigned, applicationId)) {
            throw new AccessDeniedAppException();
        }
        return caller;
    }
}
