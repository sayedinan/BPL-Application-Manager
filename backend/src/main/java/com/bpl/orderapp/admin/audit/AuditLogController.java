package com.bpl.orderapp.admin.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/audit-logs")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('SYS_ADMIN') or hasRole('ADMIN')")
public class AuditLogController {

    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public AuditLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAuditLogs(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Timestamp fromTs = parseOrNull(from, "from");
        Timestamp toTs = parseOrNull(to, "to");

        List<Map<String, Object>> rows = jdbc.query(
            "SELECT id, timestamp, actor_username, actor_role, action_type, "
                + "target_application_id, target_application_name, target_user_id, detail, result "
                + "FROM audit_logs "
                + "WHERE (timestamp >= ? OR ? IS NULL) AND (timestamp <= ? OR ? IS NULL) "
                + "ORDER BY timestamp DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                row.put("actorUsername", rs.getString("actor_username"));
                row.put("actorRole", rs.getString("actor_role"));
                row.put("actionType", rs.getString("action_type"));
                row.put("targetApplicationId", rs.getObject("target_application_id"));
                row.put("targetApplicationName", rs.getString("target_application_name"));
                row.put("targetUserId", rs.getObject("target_user_id"));
                row.put("detail", parseDetail(rs.getString("detail")));
                row.put("result", rs.getString("result"));
                return row;
            },
            fromTs, fromTs, toTs, toTs, size, page * size);

        Integer total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE (timestamp >= ? OR ? IS NULL) AND (timestamp <= ? OR ? IS NULL)",
            Integer.class, fromTs, fromTs, toTs, toTs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        body.put("page", page);
        body.put("size", size);
        body.put("total", total);
        body.put("totalPages", total == null ? 0 : (int) Math.ceil(total / (double) size));
        return ResponseEntity.ok(body);
    }

    private Timestamp parseOrNull(String iso, String paramName) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Timestamp.from(Instant.parse(iso));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                paramName + " must be an ISO-8601 instant, e.g. 2026-09-13T00:00:00Z");
        }
    }

    private Object parseDetail(String jsonbText) {
        if (jsonbText == null) return null;
        try {
            return MAPPER.readValue(jsonbText, Map.class);
        } catch (Exception e) {
            return jsonbText;
        }
    }
}
