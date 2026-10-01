package com.bpl.orderapp.admin.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the JSON body of a standard-contract health response
 * (HEALTH_CONTRACT.md) into a {@link HealthSnapshot}.
 *
 * <p>Strict about the four required fields ({@code schemaVersion},
 * {@code status}, {@code timestamp}, {@code app.name}) — if any is
 * missing or invalid, the whole response is rejected with
 * {@link InvalidHealthResponseException}.
 *
 * <p>Tolerant about everything else: an optional field that is absent,
 * has the wrong type, or is out of range (a percent above 100, a
 * negative count) is treated as "not provided" and becomes null. One
 * badly-formed optional field must never make an otherwise healthy
 * application look offline. Unknown extra fields are ignored, so newer
 * contract versions (1.1, 1.2...) keep working. Lists and text are
 * capped so a misbehaving application cannot bloat the stored payload.
 */
@Component
public class ContractParser {

    private static final int MAX_LIST_ITEMS = 50;
    private static final int MAX_TEXT_LENGTH = 500;

    private final ObjectMapper mapper;

    public ContractParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public HealthSnapshot parse(String body) {
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

        // ---- required fields ----
        String schemaVersion = text(root, "schemaVersion");
        if (schemaVersion == null) {
            throw new InvalidHealthResponseException("schemaVersion is missing");
        }
        if (!schemaVersion.matches("1\\.\\d+")) {
            throw new InvalidHealthResponseException("Unsupported schemaVersion: " + schemaVersion);
        }

        String rawStatus = text(root, "status");
        if (!HealthStatus.isValid(rawStatus)) {
            throw new InvalidHealthResponseException(
                "status is missing or not one of UP, DEGRADED, DOWN, UNKNOWN");
        }

        Instant timestamp = instant(root, "timestamp");
        if (timestamp == null) {
            throw new InvalidHealthResponseException("timestamp is missing or not ISO-8601");
        }

        JsonNode appNode = object(root, "app");
        String appName = text(appNode, "name");
        if (appName == null) {
            throw new InvalidHealthResponseException("app.name is missing");
        }

        // ---- optional parts ----
        HealthSnapshot.App app = new HealthSnapshot.App(
            appName,
            text(appNode, "description"),
            text(appNode, "environment"),
            text(appNode, "version"),
            text(appNode, "owner"),
            text(appNode, "contact"),
            links(object(appNode, "links")));

        return new HealthSnapshot(
            schemaVersion,
            HealthStatus.fromString(rawStatus),
            timestamp,
            instant(root, "startedAt"),
            app,
            build(object(root, "build")),
            resources(object(root, "resources")),
            checks(root),
            traffic(object(root, "traffic")),
            maintenance(object(root, "maintenance")),
            jobs(root));
    }

    // ------------------------------------------------------------------
    // Section builders. Each returns null when its section is absent.
    // ------------------------------------------------------------------

    private HealthSnapshot.Links links(JsonNode n) {
        if (n == null) {
            return null;
        }
        return new HealthSnapshot.Links(text(n, "app"), text(n, "docs"), text(n, "runbook"));
    }

    private HealthSnapshot.Build build(JsonNode n) {
        if (n == null) {
            return null;
        }
        return new HealthSnapshot.Build(text(n, "commit"), text(n, "branch"), instant(n, "deployedAt"));
    }

    private HealthSnapshot.Resources resources(JsonNode n) {
        if (n == null) {
            return null;
        }
        JsonNode cpu = object(n, "cpu");
        JsonNode memory = object(n, "memory");
        JsonNode disk = object(n, "disk");
        return new HealthSnapshot.Resources(
            cpu == null ? null : new HealthSnapshot.Cpu(
                percent(cpu, "processPercent"), percent(cpu, "systemPercent")),
            memory == null ? null : new HealthSnapshot.Memory(
                nonNegative(memory, "usedMb"), nonNegative(memory, "limitMb"),
                percent(memory, "usedPercent")),
            disk == null ? null : new HealthSnapshot.Disk(
                nonNegative(disk, "totalGb"), nonNegative(disk, "freeGb"),
                percent(disk, "usedPercent")));
    }

    private List<HealthSnapshot.Check> checks(JsonNode root) {
        JsonNode array = root.get("checks");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<HealthSnapshot.Check> out = new ArrayList<>();
        for (JsonNode c : array) {
            if (out.size() >= MAX_LIST_ITEMS) {
                break;
            }
            String name = c.isObject() ? text(c, "name") : null;
            if (name == null) {
                continue; // a check without a name is unusable, skip just this one
            }
            out.add(new HealthSnapshot.Check(
                name,
                text(c, "type"),
                HealthStatus.fromString(text(c, "status")),
                nonNegative(c, "latencyMs"),
                text(c, "message")));
        }
        return out;
    }

    private HealthSnapshot.Traffic traffic(JsonNode n) {
        if (n == null) {
            return null;
        }
        return new HealthSnapshot.Traffic(
            nonNegative(n, "windowMinutes"),
            count(n, "requestCount"),
            count(n, "errorCount"),
            nonNegative(n, "avgLatencyMs"));
    }

    private HealthSnapshot.Maintenance maintenance(JsonNode n) {
        if (n == null) {
            return null;
        }
        JsonNode enabled = n.get("enabled");
        Boolean enabledValue = (enabled != null && enabled.isBoolean()) ? enabled.asBoolean() : null;
        return new HealthSnapshot.Maintenance(enabledValue, text(n, "message"), instant(n, "until"));
    }

    private List<HealthSnapshot.Job> jobs(JsonNode root) {
        JsonNode array = root.get("jobs");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<HealthSnapshot.Job> out = new ArrayList<>();
        for (JsonNode j : array) {
            if (out.size() >= MAX_LIST_ITEMS) {
                break;
            }
            String name = j.isObject() ? text(j, "name") : null;
            if (name == null) {
                continue;
            }
            out.add(new HealthSnapshot.Job(
                name, instant(j, "lastRunAt"), text(j, "lastResult"), instant(j, "nextRunAt")));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Field readers. All are null-safe: a null parent, a missing field or
    // a value of the wrong type simply gives null.
    // ------------------------------------------------------------------

    private static JsonNode object(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode n = parent.get(field);
        return (n != null && n.isObject()) ? n : null;
    }

    private static String text(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode n = parent.get(field);
        if (n == null || !n.isTextual()) {
            return null;
        }
        String s = n.asText().trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > MAX_TEXT_LENGTH ? s.substring(0, MAX_TEXT_LENGTH) : s;
    }

    private static Double number(JsonNode parent, String field, double min, double max) {
        if (parent == null) {
            return null;
        }
        JsonNode n = parent.get(field);
        if (n == null || !n.isNumber()) {
            return null;
        }
        double v = n.asDouble();
        return (v >= min && v <= max) ? v : null;
    }

    private static Double percent(JsonNode parent, String field) {
        return number(parent, field, 0, 100);
    }

    private static Double nonNegative(JsonNode parent, String field) {
        return number(parent, field, 0, Double.MAX_VALUE);
    }

    private static Long count(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode n = parent.get(field);
        if (n == null || !n.isIntegralNumber() || n.asLong() < 0) {
            return null;
        }
        return n.asLong();
    }

    private static Instant instant(JsonNode parent, String field) {
        String s = text(parent, field);
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
