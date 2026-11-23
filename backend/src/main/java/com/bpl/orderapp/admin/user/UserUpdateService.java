package com.bpl.orderapp.admin.user;

import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.common.NotFoundException;
import com.bpl.orderapp.admin.user.dto.UpdateUserRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * The database work of {@code PUT /api/v1/users/{id}}, in its own transaction.
 *
 * <p>Split out of UserController so the transaction commits and its connection
 * is released BEFORE the controller writes the audit row. AuditWriter is
 * REQUIRES_NEW; SQLite has a single writer, so calling it while this transaction
 * is still open cannot get the write lock (see AuditTransactionHazardTest).
 * Do not move the audit into afterCommit either: it is unreliable here.
 */
@Service
public class UserUpdateService {

    private static final Logger log = LoggerFactory.getLogger(UserUpdateService.class);

    private final FindByIndexNameSessionRepository<?> sessionRepository;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final UserApplicationAssignmentRepository assignmentRepository;

    public UserUpdateService(FindByIndexNameSessionRepository<?> sessionRepository,
                             UserRepository userRepository,
                             ApplicationRepository applicationRepository,
                             UserApplicationAssignmentRepository assignmentRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.applicationRepository = applicationRepository;
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Applies the update and returns the audit detail (empty when nothing
     * changed, so the caller writes no audit row).
     */
    @Transactional
    public Map<String, Object> update(java.util.UUID id, UpdateUserRequest req, boolean callerIsSysAdmin) {

        // Snapshot BEFORE state so the audit row can show a diff,
        // not just the new values. Must happen before either the
        // role UPDATE or the assignment DELETE below.
        User user = userRepository.findById(id).orElseThrow(NotFoundException::new);
        String oldRole = user.getRole();
        String oldEmail = user.getEmail();
        String oldPhoneNumber = user.getPhoneNumber();
        String oldUsername = user.getUsername();
        String oldFullName = user.getFullName();
        java.util.List<Long> oldAssignedIds = assignmentRepository.findApplicationIdsByUserId(id);

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
            if (userRepository.existsByUsernameAndIdNot(req.username(), id)) {
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
            // No DB CHECK on users.role any more; reject unknown values here so
            // a bad string can never be stored (Role.valueOf would later fail on read).
            if (java.util.Arrays.stream(com.bpl.orderapp.admin.common.Role.values())
                    .noneMatch(r -> r.name().equals(req.role()))) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "role must be USER, ADMIN or SYS_ADMIN");
            }
            if (!"USER".equals(req.role()) && !callerIsSysAdmin) {
                throw new com.bpl.orderapp.admin.common.AdminCeilingException();
            }
            user.setRole(req.role());
        }
        // Effective role after this update: either the new one being
        // set, or the existing one if role isn't being changed.
        String effectiveRole = req.role() != null ? req.role() : oldRole;

        if ("USER".equals(effectiveRole)) {
            if (req.assignedApplicationIds() != null) {
                assignmentRepository.deleteAllByUserId(id);
                for (Long appId : req.assignedApplicationIds()) {
                    assignmentRepository.save(new UserApplicationAssignment(id, appId));
                }
            }
        } else if ("USER".equals(oldRole)) {
            // Promoting away from USER: clear any assignments this
            // account had, since Admin/Sys.Admin ignore them anyway —
            // leaving them behind is exactly the stale-data case
            // flagged in the frontend review above.
            assignmentRepository.deleteAllByUserId(id);
        }
        if (req.phoneNumber() != null) {
            user.setPhoneNumber(req.phoneNumber().isBlank() ? null : req.phoneNumber());
        }
        if (req.email() != null) {
            user.setEmail(req.email());
        }
        if (req.username() != null) {
            user.setUsername(req.username());
        }
        if (req.fullName() != null) {
            user.setFullName(req.fullName().trim());
        }
        userRepository.save(user);

        // The session carries the role (ROLE_<role>) and principal username from login
        // time, and a "remember me" session lives up to 30 days. Revoke so a demoted or
        // renamed user re-authenticates and picks up the current role and name.
        boolean roleChanged = req.role() != null && !req.role().equals(oldRole);
        boolean usernameChanged = req.username() != null && !req.username().equals(oldUsername);
        if (roleChanged || usernameChanged) {
            revokeSessions(oldUsername);
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

            return detail;
        } catch (Exception detailEx) {
            // Same as before: a failure while building the audit detail never fails the update.
            log.warn("Building UPDATE_USER audit detail failed (user id={})", id, detailEx);
            return Map.of();
        }
    }

    /** Deletes every live session whose principal is {@code username}. No-op for null. */
    private void revokeSessions(String username) {
        if (username == null) return;
        new java.util.ArrayList<>(sessionRepository.findByPrincipalName(username).keySet())
            .forEach(sessionRepository::deleteById);
    }

    /** Resolves application ids to "id:name" strings for readable audit details. */
    private java.util.List<String> resolveAppNames(java.util.Set<Long> ids) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Long appId : ids) {
            String name = applicationRepository.findNameById(appId).orElse("unknown");
            result.add(appId + ":" + name);
        }
        return result;
    }
}
