package com.bpl.orderapp.admin.application;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.NotFoundException;
import com.bpl.orderapp.admin.security.AccessGuard;
import com.bpl.orderapp.admin.security.Action;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/applications/{id}/group")
public class ApplicationGroupController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationGroupController.class);
    private static final int MAX_GROUP_LENGTH = 60;

    private final JdbcTemplate jdbc;
    private final AccessGuard accessGuard;
    private final AuditWriter auditWriter;

    public ApplicationGroupController(JdbcTemplate jdbc, AccessGuard accessGuard, AuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.accessGuard = accessGuard;
        this.auditWriter = auditWriter;
    }

    public record GroupRequest(String groupName) {}

    @PutMapping
    public ResponseEntity<Void> setGroup(@PathVariable Long id, @RequestBody GroupRequest req,
            HttpServletRequest httpRequest) {
        AccessGuard.Caller caller = accessGuard.require(Action.UPDATE_APPLICATION);
        String appName = applicationName(id);
        String group = cleanGroup(req.groupName());

        jdbc.update("UPDATE applications SET group_name = ? WHERE id = ?", group, id);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("change", "group");
        detail.put("group", group);
        try {
            auditWriter.write("UPDATE_APPLICATION", caller.username(), caller.role().name(),
                id, appName, null, detail, "SUCCESS", httpRequest);
        } catch (Exception e) {
            log.warn("Audit write failed for group change (application id={})", id, e);
        }
        return ResponseEntity.ok().build();
    }

    private String applicationName(Long applicationId) {
        List<String> names = jdbc.queryForList(
            "SELECT name FROM applications WHERE id = ?", String.class, applicationId);
        if (names.isEmpty()) {
            throw new NotFoundException();
        }
        return names.get(0);
    }

    private static String cleanGroup(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String group = raw.trim();
        if (group.length() > MAX_GROUP_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "groupName must be at most " + MAX_GROUP_LENGTH + " characters");
        }
        for (int i = 0; i < group.length(); i++) {
            if (Character.isISOControl(group.charAt(i))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "groupName must not contain control characters or line breaks");
            }
        }
        return group;
    }
}
