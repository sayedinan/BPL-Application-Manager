package com.bpl.orderapp.admin.health;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.common.AccessDeniedAppException;
import com.bpl.orderapp.admin.common.InvalidCredentialsException;
import com.bpl.orderapp.admin.common.NotFoundException;
import com.bpl.orderapp.admin.common.Role;
import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.bpl.orderapp.admin.security.Action;
import com.bpl.orderapp.admin.security.Rbac;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST endpoints for application health (HEALTH-MONITORING.md).
 *
 * <p>Reading health (latest and history) follows the same rule as the
 * application list: LIST_APPLICATIONS plus the per-application check, so
 * a USER only sees applications they are assigned to. Reading or
 * changing the health configuration, and testing a configuration, are
 * SYS_ADMIN only (ACCESS_APPLICATION / UPDATE_APPLICATION in Rbac); no
 * new Rbac action is needed.
 *
 * <p>Authorization is checked explicitly in this class through
 * {@link Rbac}, not with annotations, so it does not depend on method
 * security being enabled.
 *
 * <p>The API key is write-only: it is encrypted on save and never
 * returned by any endpoint, logged, or written to the audit log.
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private static final int DEFAULT_INTERVAL_SECONDS = 10;
    private static final int MIN_INTERVAL_SECONDS = 3;
    private static final int MAX_INTERVAL_SECONDS = 300;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_API_KEY_LENGTH = 512;
    private static final int MIN_HISTORY_HOURS = 1;
    private static final int MAX_HISTORY_HOURS = 720;
    private static final int HISTORY_TARGET_POINTS = 240;

    private final JdbcTemplate jdbc;
    private final SshCredentialCipher cipher;
    private final AuditWriter auditWriter;
    private final HealthCheckService healthCheckService;
    private final ObjectMapper mapper;
    private final ObjectMapper compactMapper;

    public HealthController(JdbcTemplate jdbc, SshCredentialCipher cipher, AuditWriter auditWriter,
            HealthCheckService healthCheckService, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.auditWriter = auditWriter;
        this.healthCheckService = healthCheckService;
        this.mapper = mapper;
        this.compactMapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Body for saving and testing a health configuration.
     *
     * <p>{@code apiKey}: null keeps the stored key, an empty string
     * removes it, anything else replaces it.
     * {@code tlsPinSha256}: null or empty means no pin (normal
     * certificate verification).
     */
    public record ConfigRequest(Boolean enabled, String url, String format, String apiKey,
                                String tlsPinSha256, Integer pollIntervalSeconds) {}

    // ------------------------------------------------------------------
    // Reading health: any role that can see this application
    // ------------------------------------------------------------------

    @GetMapping
    public ResponseEntity<Map<String, Object>> latest(@PathVariable Long id) {
        requireApplicationAccess(currentCaller(), id);

        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> configRows = jdbc.queryForList(
            "SELECT enabled, poll_interval_seconds FROM application_health_config WHERE application_id = ?", id);
        if (configRows.isEmpty()) {
            body.put("monitored", false);
            return ResponseEntity.ok(body);
        }
        body.put("monitored", Boolean.TRUE.equals(configRows.get(0).get("enabled")));
        body.put("pollIntervalSeconds", configRows.get(0).get("poll_interval_seconds"));

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT checked_at, reachable, http_status, response_ms, status, payload, error, "
                + "ssl_not_after, consecutive_failures "
                + "FROM application_health_latest WHERE application_id = ?", id);
        if (rows.isEmpty()) {
            body.put("checkedAt", null); // configured, first check hasn't landed yet
            return ResponseEntity.ok(body);
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
        return ResponseEntity.ok(body);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@PathVariable Long id,
            @RequestParam(defaultValue = "24") int hours) {
        requireApplicationAccess(currentCaller(), id);
        if (hours < MIN_HISTORY_HOURS || hours > MAX_HISTORY_HOURS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "hours must be between " + MIN_HISTORY_HOURS + " and " + MAX_HISTORY_HOURS);
        }
        // About 240 points whatever the window, but never finer than one minute.
        int bucketSeconds = Math.max(60, (hours * 3600 / HISTORY_TARGET_POINTS) / 60 * 60);
        Instant since = Instant.now().minusSeconds(hours * 3600L);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT floor(extract(epoch FROM checked_at) / ?) * ? AS bucket, "
                + "COUNT(*) AS checks, "
                + "COUNT(*) FILTER (WHERE NOT reachable OR status IS NULL OR status = 'DOWN') AS failed, "
                + "AVG(response_ms) AS avg_ms "
                + "FROM application_health_history "
                + "WHERE application_id = ? AND checked_at >= ? "
                + "GROUP BY bucket ORDER BY bucket",
            bucketSeconds, bucketSeconds, id, Timestamp.from(since));

        List<Map<String, Object>> points = new ArrayList<>();
        long totalChecks = 0;
        long totalFailed = 0;
        for (Map<String, Object> row : rows) {
            long checks = ((Number) row.get("checks")).longValue();
            long failed = ((Number) row.get("failed")).longValue();
            totalChecks += checks;
            totalFailed += failed;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", Instant.ofEpochSecond(((Number) row.get("bucket")).longValue()).toString());
            point.put("checks", checks);
            point.put("failedChecks", failed);
            point.put("avgResponseMs", row.get("avg_ms") == null
                ? null : Math.round(((Number) row.get("avg_ms")).doubleValue()));
            points.add(point);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hours", hours);
        body.put("bucketSeconds", bucketSeconds);
        body.put("checks", totalChecks);
        body.put("failedChecks", totalFailed);
        // Share of checks that succeeded; null when there is no data yet.
        body.put("availabilityPercent", totalChecks == 0
            ? null : Math.round(1000.0 * (totalChecks - totalFailed) / totalChecks) / 10.0);
        body.put("points", points);
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------
    // Configuration: SYS_ADMIN only
    // ------------------------------------------------------------------

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(@PathVariable Long id) {
        requireAction(currentCaller(), Action.ACCESS_APPLICATION);
        applicationName(id);

        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT enabled, url, format, api_key_enc IS NOT NULL AS has_api_key, "
                + "tls_pin_sha256, poll_interval_seconds "
                + "FROM application_health_config WHERE application_id = ?", id);
        if (rows.isEmpty()) {
            body.put("configured", false);
            return ResponseEntity.ok(body);
        }
        Map<String, Object> row = rows.get(0);
        body.put("configured", true);
        body.put("enabled", row.get("enabled"));
        body.put("url", row.get("url"));
        body.put("format", row.get("format"));
        body.put("hasApiKey", row.get("has_api_key"));
        body.put("tlsPinSha256", row.get("tls_pin_sha256"));
        body.put("pollIntervalSeconds", row.get("poll_interval_seconds"));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/config")
    public ResponseEntity<Void> saveConfig(@PathVariable Long id, @RequestBody ConfigRequest req,
            HttpServletRequest httpRequest) {
        Caller caller = currentCaller();
        requireAction(caller, Action.UPDATE_APPLICATION);
        String appName = applicationName(id);

        boolean enabled = req.enabled() == null || req.enabled();
        String url = validateUrl(req.url());
        String format = validateFormat(req.format());
        String pin = validatePin(req.tlsPinSha256(), url);
        int interval = validateInterval(req.pollIntervalSeconds());

        // null = keep the stored key, "" = remove it, anything else = replace it.
        boolean keyProvided = req.apiKey() != null;
        String encryptedKey = null;
        String keyAction = "unchanged";
        if (keyProvided) {
            String key = validateApiKey(req.apiKey());
            encryptedKey = key.isEmpty() ? null : cipher.encrypt(key);
            keyAction = key.isEmpty() ? "removed" : "set";
        }

        jdbc.update(
            "INSERT INTO application_health_config "
                + "(application_id, enabled, url, format, api_key_enc, tls_pin_sha256, poll_interval_seconds) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (application_id) DO UPDATE SET "
                + "enabled = EXCLUDED.enabled, url = EXCLUDED.url, format = EXCLUDED.format, "
                + "api_key_enc = CASE WHEN ? THEN EXCLUDED.api_key_enc "
                + "ELSE application_health_config.api_key_enc END, "
                + "tls_pin_sha256 = EXCLUDED.tls_pin_sha256, "
                + "poll_interval_seconds = EXCLUDED.poll_interval_seconds, "
                + "updated_at = NOW()",
            id, enabled, url, format, encryptedKey, pin, interval, keyProvided);

        // No secrets and no URL in the audit detail.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("change", "health_config");
        detail.put("enabled", enabled);
        detail.put("format", format);
        detail.put("tls", !url.toLowerCase(Locale.ROOT).startsWith("https") ? "none"
            : (pin != null ? "pinned" : "verified"));
        detail.put("apiKey", keyAction);
        detail.put("pollIntervalSeconds", interval);
        audit(caller, id, appName, detail, httpRequest);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/config")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id, HttpServletRequest httpRequest) {
        Caller caller = currentCaller();
        requireAction(caller, Action.UPDATE_APPLICATION);
        String appName = applicationName(id);

        int removed = jdbc.update("DELETE FROM application_health_config WHERE application_id = ?", id);
        if (removed == 0) {
            throw new NotFoundException();
        }
        jdbc.update("DELETE FROM application_health_latest WHERE application_id = ?", id);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("change", "health_config_removed");
        audit(caller, id, appName, detail, httpRequest);
        return ResponseEntity.noContent().build();
    }

    /**
     * One-off check of a configuration, without saving anything and
     * without touching online/offline state. Always answers 200: the
     * body's {@code ok} says whether the response was usable, and
     * {@code error} says why not. When the request has no apiKey the
     * stored key is used, so a saved configuration can be re-tested
     * without typing the key again.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id, @RequestBody ConfigRequest req) {
        requireAction(currentCaller(), Action.UPDATE_APPLICATION);
        String appName = applicationName(id);

        String url = validateUrl(req.url());
        String format = validateFormat(req.format());
        String pin = validatePin(req.tlsPinSha256(), url);

        String apiKey;
        if (req.apiKey() == null) {
            List<String> stored = jdbc.queryForList(
                "SELECT api_key_enc FROM application_health_config "
                    + "WHERE application_id = ? AND api_key_enc IS NOT NULL", String.class, id);
            apiKey = stored.isEmpty() ? null : cipher.decrypt(stored.get(0));
        } else {
            apiKey = validateApiKey(req.apiKey());
        }

        HealthCheckService.TestResult result = healthCheckService.test(url, format, apiKey, pin, appName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        body.put("httpStatus", result.httpStatus());
        body.put("responseMs", result.responseMs());
        body.put("status", result.status() == null ? null : result.status().name());
        body.put("error", result.error());
        body.put("sslNotAfter", result.certNotAfter() == null ? null : result.certNotAfter().toString());
        body.put("sslDaysRemaining", result.certNotAfter() == null
            ? null : ChronoUnit.DAYS.between(Instant.now(), result.certNotAfter()));
        body.put("snapshot", result.snapshot() == null ? null : compactMapper.valueToTree(result.snapshot()));
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------
    // Validation. Each returns the cleaned value or throws a 400.
    // ------------------------------------------------------------------

    private static String validateUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            throw badRequest("url is required (at most " + MAX_URL_LENGTH + " characters)");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw badRequest("url is not a valid URL");
        }
        String scheme = uri.getScheme();
        boolean schemeOk = scheme != null
            && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        if (!schemeOk || uri.getHost() == null) {
            throw badRequest("url must start with http:// or https:// and include a host");
        }
        if (uri.getUserInfo() != null) {
            throw badRequest("url must not contain a user name or password; use the API key field");
        }
        return url;
    }

    private static String validateFormat(String raw) {
        String format = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!format.equals("CONTRACT") && !format.equals("ACTUATOR")) {
            throw badRequest("format must be CONTRACT or ACTUATOR");
        }
        return format;
    }

    // Accepts the usual openssl style (upper case, with colons) and
    // stores lower-case hex without separators, as the column requires.
    private static String validatePin(String raw, String url) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String pin = raw.replace(":", "").replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        if (!pin.matches("[0-9a-f]{64}")) {
            throw badRequest("tlsPinSha256 must be a SHA-256 fingerprint (64 hex characters)");
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw badRequest("A certificate fingerprint can only be used with an https:// url");
        }
        return pin;
    }

    private static int validateInterval(Integer raw) {
        int interval = raw == null ? DEFAULT_INTERVAL_SECONDS : raw;
        if (interval < MIN_INTERVAL_SECONDS || interval > MAX_INTERVAL_SECONDS) {
            throw badRequest("pollIntervalSeconds must be between "
                + MIN_INTERVAL_SECONDS + " and " + MAX_INTERVAL_SECONDS);
        }
        return interval;
    }

    // Returns the trimmed key; an empty result means "no key".
    private static String validateApiKey(String raw) {
        String key = raw.trim();
        if (key.length() > MAX_API_KEY_LENGTH) {
            throw badRequest("apiKey must be at most " + MAX_API_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw badRequest("apiKey must not contain control characters or line breaks");
            }
        }
        return key;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    // ------------------------------------------------------------------
    // Caller and access checks (all decisions go through Rbac)
    // ------------------------------------------------------------------

    private record Caller(String username, Role role) {}

    private Caller currentCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new InvalidCredentialsException();
        }
        String username = auth.getName();
        List<String> roles = jdbc.queryForList(
            "SELECT role FROM users WHERE username = ? AND deleted_at IS NULL", String.class, username);
        if (roles.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        return new Caller(username, Role.valueOf(roles.get(0)));
    }

    private void requireAction(Caller caller, Action action) {
        if (!Rbac.canAccess(caller.role(), action)) {
            throw new AccessDeniedAppException();
        }
    }

    // Same rule as the application list: coarse gate first, then the
    // per-application check (a USER needs an assignment).
    private void requireApplicationAccess(Caller caller, Long applicationId) {
        requireAction(caller, Action.LIST_APPLICATIONS);
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
        applicationName(applicationId); // 404 if it does not exist
    }

    private String applicationName(Long applicationId) {
        List<String> names = jdbc.queryForList(
            "SELECT name FROM applications WHERE id = ?", String.class, applicationId);
        if (names.isEmpty()) {
            throw new NotFoundException();
        }
        return names.get(0);
    }

    // ------------------------------------------------------------------

    // Audit is best-effort here, as in ApplicationController: a failed
    // audit write is logged and does not undo the change.
    private void audit(Caller caller, Long applicationId, String appName,
            Map<String, Object> detail, HttpServletRequest httpRequest) {
        try {
            auditWriter.write("UPDATE_APPLICATION", caller.username(), caller.role().name(),
                applicationId, appName, null, detail, "SUCCESS", httpRequest);
        } catch (Exception e) {
            log.warn("Audit write failed for health config change (application id={})", applicationId, e);
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
