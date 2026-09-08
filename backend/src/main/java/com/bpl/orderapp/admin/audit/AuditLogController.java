package com.bpl.orderapp.admin.audit;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;
@RestController
@RequestMapping("/api/v1/audit-logs")
@org.springframework.security.access.prepost.PreAuthorize("hasRole(\"SYS_ADMIN\") or hasRole(\"ADMIN\")")
public class AuditLogController {
    private final JdbcTemplate jdbc;
    public AuditLogController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping
    public ResponseEntity<Map<String,Object>> getAuditLogs(@RequestParam(required=false) String from, @RequestParam(required=false) String to, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        List<Map<String,Object>> items = jdbc.queryForList("SELECT * FROM audit_logs WHERE (timestamp >= ? OR ? IS NULL) AND (timestamp <= ? OR ? IS NULL) ORDER BY timestamp DESC LIMIT ? OFFSET ?", from, from, to, to, size, page*size);
        return ResponseEntity.ok(Map.of("items", items, "page", page, "size", size));
    }
}
