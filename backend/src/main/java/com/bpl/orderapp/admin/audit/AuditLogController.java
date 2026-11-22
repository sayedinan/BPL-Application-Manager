package com.bpl.orderapp.admin.audit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/audit-logs")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('SYS_ADMIN') or hasRole('ADMIN')")
public class AuditLogController {

    private static final int MAX_PAGE_SIZE = 500;

    private final AuditLogReader auditLogReader;

    public AuditLogController(AuditLogReader auditLogReader) {
        this.auditLogReader = auditLogReader;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAuditLogs(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long applicationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // The audit_logs table is append-only and kept forever by design —
        // this cap only limits what a single API response can return, so
        // the UI (and any other client) can never pull back more than the
        // last MAX_PAGE_SIZE rows in one page, regardless of what `size`
        // a caller passes.
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        page = Math.max(page, 0);

        Instant fromTs = parseOrNull(from, "from");
        Instant toTs = parseOrNull(to, "to");

        AuditLogReader.Page result = auditLogReader.find(fromTs, toTs, applicationId, page, size);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuditLog entry : result.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.getId());
            row.put("timestamp", entry.getTimestamp().toString());
            row.put("actorUsername", entry.getActorUsername());
            row.put("actorRole", entry.getActorRole());
            row.put("actionType", entry.getActionType());
            row.put("targetApplicationId", entry.getTargetApplicationId());
            row.put("targetApplicationName", entry.getTargetApplicationName());
            row.put("targetUserId", entry.getTargetUserId());
            row.put("detail", entry.getDetail());
            row.put("result", entry.getResult());
            rows.add(row);
        }

        long total = result.total();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        body.put("page", page);
        body.put("size", size);
        body.put("total", total);
        body.put("totalPages", (int) Math.ceil(total / (double) size));
        return ResponseEntity.ok(body);
    }

    private Instant parseOrNull(String iso, String paramName) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                paramName + " must be an ISO-8601 instant, e.g. 2026-09-13T00:00:00Z");
        }
    }
}
