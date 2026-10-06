package com.bpl.orderapp.admin.maintenance;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/applications/{id}/maintenance")
public class MaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

    private static final int DEFAULT_MINUTES = 60;
    private static final int MAX_NOTE_LENGTH = 200;

    private final JdbcTemplate jdbc;
    private final AccessGuard accessGuard;
    private final AuditWriter auditWriter;
    private final MaintenanceService maintenanceService;

    public MaintenanceController(JdbcTemplate jdbc, AccessGuard accessGuard, AuditWriter auditWriter,
            MaintenanceService maintenanceService) {
        this.jdbc = jdbc;
        this.accessGuard = accessGuard;
        this.auditWriter = auditWriter;
        this.maintenanceService = maintenanceService;
    }

    public record StartRequest(Integer minutes, String note) {}

    @PutMapping
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long id, @RequestBody StartRequest req,
            HttpServletRequest httpRequest) {
        AccessGuard.Caller caller = accessGuard.requireApplication(Action.CONTROL_APPLICATION, id);
        String appName = applicationName(id);

        int minutes = req.minutes() == null ? DEFAULT_MINUTES : req.minutes();
        if (minutes < MaintenanceService.MIN_MINUTES || minutes > MaintenanceService.MAX_MINUTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "minutes must be between " + MaintenanceService.MIN_MINUTES + " and " + MaintenanceService.MAX_MINUTES);
        }
        String note = cleanNote(req.note());

        Instant endsAt = maintenanceService.start(id, minutes, note, caller.username());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("change", "maintenance_started");
        detail.put("minutes", minutes);
        if (note != null) {
            detail.put("note", note);
        }
        audit(caller, id, appName, detail, httpRequest);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endsAt", endsAt.toString());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping
    public ResponseEntity<Void> end(@PathVariable Long id, HttpServletRequest httpRequest) {
        AccessGuard.Caller caller = accessGuard.requireApplication(Action.CONTROL_APPLICATION, id);
        String appName = applicationName(id);

        if (maintenanceService.end(id)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("change", "maintenance_ended");
            audit(caller, id, appName, detail, httpRequest);
        }
        return ResponseEntity.noContent().build();
    }

    private String applicationName(Long applicationId) {
        List<String> names = jdbc.queryForList(
            "SELECT name FROM applications WHERE id = ?", String.class, applicationId);
        if (names.isEmpty()) {
            throw new NotFoundException();
        }
        return names.get(0);
    }

    private static String cleanNote(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String note = raw.trim();
        if (note.length() > MAX_NOTE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
        for (int i = 0; i < note.length(); i++) {
            if (Character.isISOControl(note.charAt(i))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "note must not contain control characters or line breaks");
            }
        }
        return note;
    }

    private void audit(AccessGuard.Caller caller, Long applicationId, String appName,
            Map<String, Object> detail, HttpServletRequest httpRequest) {
        try {
            auditWriter.write("UPDATE_APPLICATION", caller.username(), caller.role().name(),
                applicationId, appName, null, detail, "SUCCESS", httpRequest);
        } catch (Exception e) {
            log.warn("Audit write failed for maintenance change (application id={})", applicationId, e);
        }
    }
}
