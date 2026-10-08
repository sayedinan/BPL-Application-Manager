package com.bpl.orderapp.admin.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link ContractParser}: the standard health response of
 * HEALTH_CONTRACT.md. No Spring context and no database.
 */
class ContractParserTest {

    private final ContractParser parser = new ContractParser(new ObjectMapper());

    private static final String MINIMAL = """
        {"schemaVersion":"1.0","status":"UP","timestamp":"2026-10-01T08:12:20Z","app":{"name":"Order Engine"}}""";

    private static final String FULL = """
        {
          "schemaVersion": "1.0",
          "status": "DEGRADED",
          "timestamp": "2026-10-01T08:12:20Z",
          "startedAt": "2026-10-01T04:07:15Z",
          "app": {
            "name": "Order Engine",
            "description": "Processes incoming orders",
            "environment": "production",
            "version": "2.3.1",
            "owner": "Order Team",
            "contact": "order-team@company.com",
            "links": {
              "app": "https://orders.internal.example",
              "docs": "https://wiki.internal.example/order-engine",
              "runbook": "https://wiki.internal.example/order-engine/runbook"
            }
          },
          "build": { "commit": "8cb8234b", "branch": "release/2.3", "deployedAt": "2026-10-01T04:05:00Z" },
          "resources": {
            "cpu": { "processPercent": 2.1, "systemPercent": 11.4 },
            "memory": { "usedMb": 108, "limitMb": 2048, "usedPercent": 5.3 },
            "disk": { "totalGb": 491, "freeGb": 356, "usedPercent": 27.5 }
          },
          "checks": [
            { "name": "postgres", "type": "database", "status": "UP", "latencyMs": 4 },
            { "name": "redis", "type": "cache", "status": "UP", "latencyMs": 1 },
            { "name": "rabbitmq", "type": "queue", "status": "UP" },
            { "name": "payment-gateway", "type": "external_api", "status": "UP", "latencyMs": 180 },
            { "name": "email", "type": "notification", "status": "DOWN", "message": "SMTP connection refused" }
          ],
          "traffic": { "windowMinutes": 5, "requestCount": 1200, "errorCount": 3, "avgLatencyMs": 85 },
          "maintenance": { "enabled": false },
          "jobs": [
            { "name": "nightly-settlement", "lastRunAt": "2026-10-01T01:00:00Z", "lastResult": "SUCCESS",
              "nextRunAt": "2026-10-02T01:00:00Z" }
          ]
        }""";

    private static String withTail(String tail) {
        return "{\"schemaVersion\":\"1.0\",\"status\":\"UP\",\"timestamp\":\"2026-10-01T08:12:20Z\","
            + "\"app\":{\"name\":\"X\"}" + tail + "}";
    }

    private void assertRejected(String json, String messagePart) {
        assertThatThrownBy(() -> parser.parse(json))
            .isInstanceOf(InvalidHealthResponseException.class)
            .hasMessageContaining(messagePart);
    }

    @Test
    void minimalResponse_isAccepted() {
        HealthSnapshot snapshot = parser.parse(MINIMAL);
        assertThat(snapshot.status()).isEqualTo(HealthStatus.UP);
        assertThat(snapshot.schemaVersion()).isEqualTo("1.0");
        assertThat(snapshot.timestamp()).isEqualTo(Instant.parse("2026-10-01T08:12:20Z"));
        assertThat(snapshot.app().name()).isEqualTo("Order Engine");
        assertThat(snapshot.startedAt()).isNull();
        assertThat(snapshot.resources()).isNull();
        assertThat(snapshot.checks()).isNull();
        assertThat(snapshot.traffic()).isNull();
    }

    @Test
    void fullExample_isReadIntoEveryPart() {
        HealthSnapshot snapshot = parser.parse(FULL);
        assertThat(snapshot.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(snapshot.startedAt()).isEqualTo(Instant.parse("2026-10-01T04:07:15Z"));
        assertThat(snapshot.app().environment()).isEqualTo("production");
        assertThat(snapshot.app().version()).isEqualTo("2.3.1");
        assertThat(snapshot.app().owner()).isEqualTo("Order Team");
        assertThat(snapshot.app().contact()).isEqualTo("order-team@company.com");
        assertThat(snapshot.app().links().runbook()).isEqualTo("https://wiki.internal.example/order-engine/runbook");
        assertThat(snapshot.build().commit()).isEqualTo("8cb8234b");
        assertThat(snapshot.build().deployedAt()).isEqualTo(Instant.parse("2026-10-01T04:05:00Z"));
        assertThat(snapshot.resources().cpu().systemPercent()).isEqualTo(11.4);
        assertThat(snapshot.resources().cpu().processPercent()).isEqualTo(2.1);
        assertThat(snapshot.resources().memory().usedMb()).isEqualTo(108.0);
        assertThat(snapshot.resources().memory().usedPercent()).isEqualTo(5.3);
        assertThat(snapshot.resources().disk().freeGb()).isEqualTo(356.0);
        assertThat(snapshot.checks()).hasSize(5);
        assertThat(snapshot.checks().get(4).name()).isEqualTo("email");
        assertThat(snapshot.checks().get(4).status()).isEqualTo(HealthStatus.DOWN);
        assertThat(snapshot.checks().get(4).message()).isEqualTo("SMTP connection refused");
        assertThat(snapshot.traffic().requestCount()).isEqualTo(1200L);
        assertThat(snapshot.traffic().errorCount()).isEqualTo(3L);
        assertThat(snapshot.maintenance().enabled()).isFalse();
        assertThat(snapshot.jobs()).hasSize(1);
        assertThat(snapshot.jobs().get(0).lastResult()).isEqualTo("SUCCESS");
    }

    @Test
    void statusIsCaseInsensitive() {
        assertThat(parser.parse(MINIMAL.replace("\"UP\"", "\"up\"")).status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void timestampWithOffset_isConvertedToUtc() {
        HealthSnapshot snapshot = parser.parse(MINIMAL.replace("2026-10-01T08:12:20Z", "2026-10-01T14:12:20+06:00"));
        assertThat(snapshot.timestamp()).isEqualTo(Instant.parse("2026-10-01T08:12:20Z"));
    }

    @Test
    void laterMinorVersions_andUnknownFields_areAccepted() {
        String json = MINIMAL.replace("\"1.0\"", "\"1.7\"").replace("}}", "},\"somethingNew\":{\"a\":1}}");
        assertThat(parser.parse(json).schemaVersion()).isEqualTo("1.7");
    }

    @Test
    void missingSchemaVersion_isRejected() {
        assertRejected(MINIMAL.replace("\"schemaVersion\":\"1.0\",", ""), "schemaVersion is missing");
    }

    @Test
    void otherMajorVersion_isRejected() {
        assertRejected(MINIMAL.replace("\"1.0\"", "\"2.0\""), "Unsupported schemaVersion");
    }

    @Test
    void missingStatus_isRejected() {
        assertRejected(MINIMAL.replace("\"status\":\"UP\",", ""), "status is missing");
    }

    @Test
    void madeUpStatus_isRejected() {
        assertRejected(MINIMAL.replace("\"UP\"", "\"FINE\""), "status is missing or not one of");
    }

    @Test
    void missingTimestamp_isRejected() {
        assertRejected(MINIMAL.replace("\"timestamp\":\"2026-10-01T08:12:20Z\",", ""), "timestamp is missing");
    }

    @Test
    void timestampThatIsNotIso8601_isRejected() {
        assertRejected(MINIMAL.replace("2026-10-01T08:12:20Z", "yesterday"), "timestamp is missing or not ISO-8601");
    }

    @Test
    void missingAppName_isRejected() {
        assertRejected(MINIMAL.replace("\"app\":{\"name\":\"Order Engine\"}", "\"app\":{}"), "app.name is missing");
    }

    @Test
    void responsesThatAreNotJsonObjects_areRejected() {
        assertRejected("[]", "not a JSON object");
        assertRejected("not json at all", "not valid JSON");
        assertRejected("   ", "empty");
        assertRejected(null, "empty");
    }

    @Test
    void outOfRangeOptionalNumbers_becomeNotProvided() {
        HealthSnapshot snapshot = parser.parse(withTail(
            ",\"resources\":{\"cpu\":{\"processPercent\":250,\"systemPercent\":-1}},"
                + "\"traffic\":{\"requestCount\":-5,\"errorCount\":2}"));
        assertThat(snapshot.status()).isEqualTo(HealthStatus.UP);
        assertThat(snapshot.resources().cpu().processPercent()).isNull();
        assertThat(snapshot.resources().cpu().systemPercent()).isNull();
        assertThat(snapshot.traffic().requestCount()).isNull();
        assertThat(snapshot.traffic().errorCount()).isEqualTo(2L);
    }

    @Test
    void wrongTypedOptionalParts_becomeNotProvided() {
        HealthSnapshot snapshot = parser.parse(withTail(
            ",\"resources\":{\"memory\":\"lots\"},\"checks\":\"none\",\"build\":{\"commit\":42}"));
        assertThat(snapshot.resources().memory()).isNull();
        assertThat(snapshot.checks()).isNull();
        assertThat(snapshot.build().commit()).isNull();
    }

    @Test
    void checksWithoutAName_areSkipped_andUnknownStatusesBecomeUnknown() {
        HealthSnapshot snapshot = parser.parse(withTail(
            ",\"checks\":[{\"status\":\"UP\"},{\"name\":\"db\",\"status\":\"MAYBE\"},\"junk\"]"));
        assertThat(snapshot.checks()).hasSize(1);
        assertThat(snapshot.checks().get(0).name()).isEqualTo("db");
        assertThat(snapshot.checks().get(0).status()).isEqualTo(HealthStatus.UNKNOWN);
    }

    @Test
    void longLists_andLongText_areCapped() {
        StringBuilder checks = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            if (i > 0) {
                checks.append(',');
            }
            checks.append("{\"name\":\"c").append(i).append("\",\"status\":\"UP\"}");
        }
        HealthSnapshot many = parser.parse(withTail(",\"checks\":[" + checks + "]"));
        assertThat(many.checks()).hasSize(50);

        String longText = "d".repeat(600);
        HealthSnapshot wordy = parser.parse(MINIMAL.replace("\"app\":{", "\"app\":{\"description\":\"" + longText + "\",").replace("\"name\":\"Order Engine\"", "\"name\":\"X\""));
        assertThat(wordy.app().description()).hasSize(500);
    }
}
