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
    private final ApplicationHealthConfigRepository configRepository;
    private final ApplicationHealthLatestRepository latestRepository;

    public HealthReadService(JdbcTemplate jdbc, ObjectMapper mapper, WebSocketBroadcast broadcast,
                             ApplicationHealthConfigRepository configRepository,
                             ApplicationHealthLatestRepository latestRepository) {
        this.latestRepository = latestRepository;
        this.configRepository = configRepository;
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
            "SELECT starts_at, ends_at, note, started_by FROM application_maintenance "
                + "WHERE application_id = ? AND ends_at > NOW()", applicationId);
        if (!maintenance.isEmpty()) {
            body.put("maintenanceStartsAt", iso(maintenance.get(0).get("starts_at")));
            body.put("maintenanceUntil", iso(maintenance.get(0).get("ends_at")));
            body.put("maintenanceNote", maintenance.get(0).get("note"));
            body.put("maintenanceBy", maintenance.get(0).get("started_by"));
        }
        java.util.Optional<ApplicationHealthConfig> config = configRepository.findById(applicationId);
        if (config.isEmpty()) {
            body.put("monitored", false);
            return body;
        }
        body.put("monitored", config.get().isEnabled());
        body.put("pollIntervalSeconds", config.get().getPollIntervalSeconds());

        java.util.Optional<ApplicationHealthLatest> found = latestRepository.findById(applicationId);
        if (found.isEmpty()) {
            body.put("checkedAt", null); // configured, first check hasn't landed yet
            return body;
        }
        ApplicationHealthLatest row = found.get();
        body.put("checkedAt", row.getCheckedAt().toString());
        body.put("reachable", row.isReachable());
        body.put("httpStatus", row.getHttpStatus());
        body.put("responseMs", row.getResponseMs());
        body.put("status", row.getStatus());
        body.put("error", row.getError());
        body.put("consecutiveFailures", row.getConsecutiveFailures());
        Instant sslNotAfter = row.getSslNotAfter();
        body.put("sslNotAfter", sslNotAfter == null ? null : sslNotAfter.toString());
        body.put("sslDaysRemaining",
            sslNotAfter == null ? null : ChronoUnit.DAYS.between(Instant.now(), sslNotAfter));
        body.put("snapshot", parseJson(row.getPayload()));
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
