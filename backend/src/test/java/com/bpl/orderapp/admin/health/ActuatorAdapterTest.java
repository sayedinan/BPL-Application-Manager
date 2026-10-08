package com.bpl.orderapp.admin.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ActuatorAdapterTest {

    private final ActuatorAdapter adapter = new ActuatorAdapter(new ObjectMapper());

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-01T08:12:30Z");

    private static final String DEV_SAMPLE = """
        {
          "components": {
            "application": {
              "details": {
                "name": "unknown",
                "environment": "default",
                "version": "0.0.1-SNAPSHOT",
                "gitCommit": "8cb8234b",
                "gitBranch": "work_order",
                "uptime": "PT4H5M5.528S",
                "uptimeSeconds": 14705,
                "timestamp": "2026-10-01T08:12:20.538677932Z"
              },
              "status": "UP"
            },
            "db": {
              "details": { "database": "PostgreSQL", "validationQuery": "isValid()" },
              "status": "UP"
            },
            "diskSpace": {
              "details": {
                "total": 527292432384,
                "free": 382498435072,
                "threshold": 10485760,
                "path": "/.",
                "exists": true
              },
              "status": "UP"
            },
            "livenessState": { "status": "UP" },
            "notifyx": {
              "details": { "console": "UP", "sms": "UNKNOWN", "email": "UNKNOWN" },
              "status": "DEGRADED"
            },
            "ping": { "status": "UP" },
            "readinessState": { "status": "UP" },
            "resourceUsage": {
              "details": {
                "memoryUsedMb": 108,
                "memoryLimitMb": 23280,
                "memoryUsagePercent": 0.5,
                "processCpuUsagePercent": 0,
                "systemCpuUsagePercent": 1.3
              },
              "status": "UP"
            },
            "ssl": {
              "details": { "expiringChains": [], "invalidChains": [], "validChains": [] },
              "status": "UP"
            }
          },
          "groups": [ "liveness", "readiness" ],
          "status": "UP"
        }""";

    private HealthSnapshot parse(String json) {
        return adapter.parse(json, "Order Engine", RECEIVED_AT);
    }

    @Test
    void devSample_overallStatusIsDegraded_becauseOneComponentIs() {
        assertThat(parse(DEV_SAMPLE).status()).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void devSample_identityAndTime() {
        HealthSnapshot snapshot = parse(DEV_SAMPLE);
        Instant reportedAt = Instant.parse("2026-10-01T08:12:20.538677932Z");
        assertThat(snapshot.timestamp()).isEqualTo(reportedAt);
        assertThat(snapshot.startedAt()).isEqualTo(reportedAt.minusSeconds(14705));
        assertThat(snapshot.app().name()).isEqualTo("Order Engine");
        assertThat(snapshot.app().environment()).isEqualTo("default");
        assertThat(snapshot.app().version()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(snapshot.build().commit()).isEqualTo("8cb8234b");
        assertThat(snapshot.build().branch()).isEqualTo("work_order");
        assertThat(snapshot.schemaVersion()).isEqualTo("1.0");
    }

    @Test
    void devSample_resources() {
        HealthSnapshot.Resources resources = parse(DEV_SAMPLE).resources();
        assertThat(resources.cpu().processPercent()).isEqualTo(0.0);
        assertThat(resources.cpu().systemPercent()).isEqualTo(1.3);
        assertThat(resources.memory().usedMb()).isEqualTo(108.0);
        assertThat(resources.memory().limitMb()).isEqualTo(23280.0);
        assertThat(resources.memory().usedPercent()).isEqualTo(0.5);
        assertThat(resources.disk().totalGb()).isEqualTo(491.1);
        assertThat(resources.disk().freeGb()).isEqualTo(356.2);
        assertThat(resources.disk().usedPercent()).isEqualTo(27.5);
    }

    @Test
    void devSample_onlyDependenciesAreListedAsChecks() {
        HealthSnapshot snapshot = parse(DEV_SAMPLE);
        assertThat(snapshot.checks()).hasSize(2);
        HealthSnapshot.Check db = snapshot.checks().get(0);
        assertThat(db.name()).isEqualTo("db");
        assertThat(db.type()).isEqualTo("database");
        assertThat(db.status()).isEqualTo(HealthStatus.UP);
        assertThat(db.message()).isNull();
        HealthSnapshot.Check notifyx = snapshot.checks().get(1);
        assertThat(notifyx.name()).isEqualTo("notifyx");
        assertThat(notifyx.type()).isEqualTo("notification");
        assertThat(notifyx.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(notifyx.message()).isEqualTo("sms: UNKNOWN, email: UNKNOWN");
    }

    @Test
    void healthyApplication_staysUp() {
        HealthSnapshot snapshot = parse("{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"}}}");
        assertThat(snapshot.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void outOfService_isDown() {
        assertThat(parse("{\"status\":\"OUT_OF_SERVICE\"}").status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void detailsHidden_onlyTheOverallStatusIsKnown() {
        HealthSnapshot snapshot = parse("{\"status\":\"UP\"}");
        assertThat(snapshot.status()).isEqualTo(HealthStatus.UP);
        assertThat(snapshot.timestamp()).isEqualTo(RECEIVED_AT);
        assertThat(snapshot.app().name()).isEqualTo("Order Engine");
        assertThat(snapshot.resources()).isNull();
        assertThat(snapshot.checks()).isNull();
        assertThat(snapshot.startedAt()).isNull();
    }

    @Test
    void downDependency_carriesActuatorsErrorText() {
        HealthSnapshot snapshot = parse(
            "{\"status\":\"DOWN\",\"components\":{\"redis\":{\"status\":\"DOWN\","
                + "\"details\":{\"error\":\"Connection refused\"}}}}");
        assertThat(snapshot.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(snapshot.checks()).hasSize(1);
        assertThat(snapshot.checks().get(0).type()).isEqualTo("cache");
        assertThat(snapshot.checks().get(0).message()).isEqualTo("Connection refused");
    }

    @Test
    void anApplicationNamedByItself_keepsItsOwnName() {
        HealthSnapshot snapshot = parse(
            "{\"status\":\"UP\",\"components\":{\"application\":{\"status\":\"UP\","
                + "\"details\":{\"name\":\"orders-service\"}}}}");
        assertThat(snapshot.app().name()).isEqualTo("orders-service");
    }

    @Test
    void responsesWithoutAStatus_orNotJson_areRejected() {
        assertThatThrownBy(() -> parse("{\"components\":{}}"))
            .isInstanceOf(InvalidHealthResponseException.class)
            .hasMessageContaining("status is missing");
        assertThatThrownBy(() -> parse("<html>502</html>"))
            .isInstanceOf(InvalidHealthResponseException.class)
            .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> parse(""))
            .isInstanceOf(InvalidHealthResponseException.class);
    }
}
