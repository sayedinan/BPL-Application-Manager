package com.bpl.orderapp.admin.health;

import com.bpl.orderapp.admin.log.WebSocketBroadcast;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the "latest health" view of one application and pushes it to
 * the browsers watching it.
 *
 * <p>One builder serves both ways of getting the data, so the REST
 * response ({@code GET /applications/{id}/health}) and the WebSocket
 * message ({@code /topic/application-health/{id}}) always have exactly
 * the same shape and the frontend handles them with one code path.
 *
 * <p>Pushing is best-effort. Nothing waits on a browser, a failed push
 * is only logged, and the frontend still polls as a safety net.
 */
@Component
public class HealthReadService {

    private static final Logger log = LoggerFactory.getLogger(HealthReadService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebSocketBroadcast broadcast;

    public HealthReadService(JdbcTemplate jdbc, ObjectMapper mapper, WebSocketBroadcast broadcast) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.broadcast = broadcast;
    }

    /** The latest health of an application; {@code monitored} is false when it has no health config. */
    public Map<String, Object> latest(Long applicationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        // An open maintenance window (alerts held back) is shown for every
        // application, whether or not it is monitored through a health endpoint.
        List<Map<String, Object>> maintenance = jdbc.queryForList(
            "SELECT ends_at, note, started_by FROM application_maintenance "
                + "WHERE application_id = ? AND ends_at > NOW()", applicationId);
        if (!maintenance.isEmpty()) {
            body.put("maintenanceUntil", iso(maintenance.get(0).get("ends_at")));
            body.put("maintenanceNote", maintenance.get(0).get("note"));
            body.put("maintenanceBy", maintenance.get(0).get("started_by"));
        }
        List<Map<String, Object>> configRows = jdbc.queryForList(
            "SELECT enabled, poll_interval_seconds FROM application_health_config WHERE application_id = ?",
            applicationId);
        if (configRows.isEmpty()) {
            body.put("monitored", false);
            return body;
        }
        body.put("monitored", Boolean.TRUE.equals(configRows.get(0).get("enabled")));
        body.put("pollIntervalSeconds", configRows.get(0).get("poll_interval_seconds"));

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT checked_at, reachable, http_status, response_ms, status, payload, error, "
                + "ssl_not_after, consecutive_failures "
                + "FROM application_health_latest WHERE application_id = ?", applicationId);
        if (rows.isEmpty()) {
            body.put("checkedAt", null); // configured, first check hasn't landed yet
            return body;
        }
        Map<String, Object> row = rows.get(0);
        body.put("checkedAt", iso(row.get("checked_at")));
        body.put("reachable", row.get("reachable"));
        body.put("httpStatus", row.get("http_status"));
        body.put("responseMs", row.get("response_ms"));
        body.put("status", row.get("status"));
        body.put("error", row.get("error"));
        body.put("consecutiveFailures", row.get("consecutive_failures"));
        Instant sslNotAfter = row.get("ssl_not_after") == null
            ? null : ((Timestamp) row.get("ssl_not_after")).toInstant();
        body.put("sslNotAfter", sslNotAfter == null ? null : sslNotAfter.toString());
        body.put("sslDaysRemaining",
            sslNotAfter == null ? null : ChronoUnit.DAYS.between(Instant.now(), sslNotAfter));
        body.put("snapshot", parseJson(row.get("payload")));
        return body;
    }

    /** Sends the application's current health to everyone subscribed to it. */
    public void publish(Long applicationId) {
        try {
            broadcast.broadcastHealth(applicationId, latest(applicationId));
        } catch (RuntimeException e) {
            log.warn("Could not push health for application id={}", applicationId, e);
        }
    }

    private static String iso(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant().toString();
    }

    // The payload column is JSONB, which the driver hands back as a
    // PGobject; its string form is the JSON text.
    private JsonNode parseJson(Object jsonb) {
        if (jsonb == null) {
            return null;
        }
        try {
            return mapper.readTree(jsonb.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
