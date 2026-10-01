package com.bpl.orderapp.admin.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a Spring Boot Actuator {@code /actuator/health} response into
 * a {@link HealthSnapshot}, so an application that already speaks
 * Actuator needs no changes to be monitored (HEALTH-MONITORING.md D1).
 *
 * <p>Works with any Spring Boot application: the overall status, the
 * dependency components (db, redis, rabbit, mail...) and the standard
 * {@code diskSpace} component are read generically. If an application
 * also publishes custom {@code application} and {@code resourceUsage}
 * components (as the first monitored application does), name,
 * environment, version, git commit/branch, uptime, CPU and memory are
 * picked up from them; if not, those parts of the snapshot stay null.
 * With Actuator's {@code show-details=never} only the overall status
 * is available, and that is still enough to decide online/offline.
 *
 * <p>Status mapping: UP and DOWN map directly, OUT_OF_SERVICE becomes
 * DOWN, and a custom DEGRADED is kept. Actuator's own aggregation does
 * not know DEGRADED and reports the overall status as UP while a
 * component is degraded, so when the overall status is UP but any
 * component is DEGRADED or DOWN, the snapshot is reported as DEGRADED.
 */
@Component
public class ActuatorAdapter {

    private static final double BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0;
    private static final int MAX_LIST_ITEMS = 50;
    private static final int MAX_MESSAGE_ENTRIES = 3;
    private static final int MAX_MESSAGE_VALUE_LENGTH = 100;

    // Components that describe the application itself or its host, not
    // a dependency — read separately above, never listed as checks.
    private static final Set<String> NOT_CHECKS = Set.of(
        "application", "resourceUsage", "diskSpace",
        "livenessState", "readinessState", "ping", "ssl");

    private final ObjectMapper mapper;

    public ActuatorAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param fallbackAppName the name the application is registered under in
     *                        this dashboard; used because Actuator has no
     *                        standard application-name field
     * @param receivedAt      when the response arrived; used as the snapshot
     *                        timestamp because Actuator has no standard one
     */
    public HealthSnapshot parse(String body, String fallbackAppName, Instant receivedAt) {
        if (body == null || body.isBlank()) {
            throw new InvalidHealthResponseException("Response body is empty");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidHealthResponseException("Response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidHealthResponseException("Response is not a JSON object");
        }

        String rawStatus = ContractParser.text(root, "status");
        if (rawStatus == null) {
            throw new InvalidHealthResponseException("status is missing");
        }
        HealthStatus overall = mapStatus(rawStatus);

        // Absent when Actuator runs with show-details=never.
        JsonNode components = ContractParser.object(root, "components");
        JsonNode appDetails = details(components, "application");
        JsonNode usageDetails = details(components, "resourceUsage");
        JsonNode diskDetails = details(components, "diskSpace");

        if (overall == HealthStatus.UP && anyComponentImpaired(components)) {
            overall = HealthStatus.DEGRADED;
        }

        Instant timestamp = ContractParser.instant(appDetails, "timestamp");
        if (timestamp == null) {
            timestamp = receivedAt;
        }
        Instant startedAt = null;
        Double uptimeSeconds = ContractParser.nonNegative(appDetails, "uptimeSeconds");
        if (uptimeSeconds != null) {
            startedAt = timestamp.minusSeconds(uptimeSeconds.longValue());
        }

        // Spring reports "unknown" when spring.application.name is not set.
        String name = ContractParser.text(appDetails, "name");
        if (name == null || name.equalsIgnoreCase("unknown")) {
            name = fallbackAppName;
        }
        HealthSnapshot.App app = new HealthSnapshot.App(
            name, null,
            ContractParser.text(appDetails, "environment"),
            ContractParser.text(appDetails, "version"),
            null, null, null);

        String commit = ContractParser.text(appDetails, "gitCommit");
        String branch = ContractParser.text(appDetails, "gitBranch");
        HealthSnapshot.Build build = (commit == null && branch == null)
            ? null : new HealthSnapshot.Build(commit, branch, null);

        return new HealthSnapshot(
            "1.0", overall, timestamp, startedAt, app, build,
            resources(usageDetails, diskDetails),
            checks(components),
            null, null, null);
    }

    // ------------------------------------------------------------------

    private static HealthStatus mapStatus(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (s.equals("OUT_OF_SERVICE")) {
            return HealthStatus.DOWN;
        }
        return HealthStatus.fromString(s);
    }

    private static JsonNode details(JsonNode components, String name) {
        return ContractParser.object(ContractParser.object(components, name), "details");
    }

    private static boolean anyComponentImpaired(JsonNode components) {
        if (components == null) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> it = components.fields();
        while (it.hasNext()) {
            HealthStatus s = mapStatus(ContractParser.text(it.next().getValue(), "status"));
            if (s == HealthStatus.DEGRADED || s == HealthStatus.DOWN) {
                return true;
            }
        }
        return false;
    }

    private static HealthSnapshot.Resources resources(JsonNode usage, JsonNode disk) {
        HealthSnapshot.Cpu cpu = usage == null ? null : new HealthSnapshot.Cpu(
            ContractParser.percent(usage, "processCpuUsagePercent"),
            ContractParser.percent(usage, "systemCpuUsagePercent"));
        HealthSnapshot.Memory memory = usage == null ? null : new HealthSnapshot.Memory(
            ContractParser.nonNegative(usage, "memoryUsedMb"),
            ContractParser.nonNegative(usage, "memoryLimitMb"),
            ContractParser.percent(usage, "memoryUsagePercent"));
        HealthSnapshot.Disk diskInfo = disk(disk);
        if (cpu == null && memory == null && diskInfo == null) {
            return null;
        }
        return new HealthSnapshot.Resources(cpu, memory, diskInfo);
    }

    // Actuator's standard diskSpace component reports bytes.
    private static HealthSnapshot.Disk disk(JsonNode disk) {
        Double total = ContractParser.number(disk, "total", 1, Double.MAX_VALUE);
        Double free = ContractParser.nonNegative(disk, "free");
        if (total == null || free == null || free > total) {
            return null;
        }
        return new HealthSnapshot.Disk(
            round1(total / BYTES_PER_GB),
            round1(free / BYTES_PER_GB),
            round1((total - free) / total * 100.0));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static List<HealthSnapshot.Check> checks(JsonNode components) {
        if (components == null) {
            return null;
        }
        List<HealthSnapshot.Check> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = components.fields();
        while (it.hasNext() && out.size() < MAX_LIST_ITEMS) {
            Map.Entry<String, JsonNode> e = it.next();
            if (NOT_CHECKS.contains(e.getKey()) || !e.getValue().isObject()) {
                continue;
            }
            JsonNode c = e.getValue();
            HealthStatus status = mapStatus(ContractParser.text(c, "status"));
            String message = status == HealthStatus.UP
                ? null : message(ContractParser.object(c, "details"));
            out.add(new HealthSnapshot.Check(e.getKey(), typeOf(e.getKey()), status, null, message));
        }
        return out;
    }

    // Short explanation for a check that is not UP: Actuator's "error"
    // field if present, else the first few text details that are not
    // themselves UP (e.g. "sms: UNKNOWN, email: UNKNOWN").
    private static String message(JsonNode details) {
        if (details == null) {
            return null;
        }
        String error = ContractParser.text(details, "error");
        if (error != null) {
            return error;
        }
        List<String> parts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = details.fields();
        while (it.hasNext() && parts.size() < MAX_MESSAGE_ENTRIES) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isTextual() && !v.asText().equalsIgnoreCase("UP")) {
                String value = v.asText();
                if (value.length() > MAX_MESSAGE_VALUE_LENGTH) {
                    value = value.substring(0, MAX_MESSAGE_VALUE_LENGTH);
                }
                parts.add(e.getKey() + ": " + value);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String typeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("db") || n.contains("jdbc") || n.contains("postgres") || n.contains("mysql")
                || n.contains("mongo") || n.contains("oracle")) {
            return "database";
        }
        if (n.contains("redis") || n.contains("cache") || n.contains("memcache")) {
            return "cache";
        }
        if (n.contains("rabbit") || n.contains("kafka") || n.contains("jms")
                || n.contains("activemq") || n.contains("queue")) {
            return "queue";
        }
        if (n.contains("mail") || n.contains("sms") || n.contains("notif")) {
            return "notification";
        }
        return "other";
    }
}
