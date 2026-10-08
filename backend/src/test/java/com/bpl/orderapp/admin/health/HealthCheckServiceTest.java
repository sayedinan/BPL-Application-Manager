package com.bpl.orderapp.admin.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthCheckServiceTest {

    private static final Long APP_ID = 7L;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HealthHttpClient httpClient = mock(HealthHttpClient.class);
    private final HealthReadService readService = mock(HealthReadService.class);
    private final SshCredentialCipher cipher = mock(SshCredentialCipher.class);

    private boolean configured = true;
    private String format = "CONTRACT";
    private final AtomicInteger storedFailures = new AtomicInteger(0);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, this::fakeDatabase);
    private final HealthCheckService service = new HealthCheckService(
        jdbc, cipher, httpClient, new ContractParser(mapper), new ActuatorAdapter(mapper), mapper, readService);

    private Object fakeDatabase(InvocationOnMock call) {
        String sql = call.getArgument(0);
        if (sql.startsWith("SELECT c.url")) {
            if (!configured) {
                return List.of();
            }
            Map<String, Object> row = new HashMap<>();
            row.put("url", "http://app.test/status");
            row.put("format", format);
            row.put("api_key_enc", null);
            row.put("tls_pin_sha256", null);
            row.put("poll_interval_seconds", 10);
            row.put("name", "Order Engine");
            return List.of(row);
        }
        if (sql.startsWith("SELECT consecutive_failures")) {
            return List.of(storedFailures.get());
        }
        if (sql.startsWith("INSERT INTO application_health_latest")) {
            Object[] arguments = call.getArguments();
            storedFailures.set((Integer) arguments[arguments.length - 1]);
            return 1;
        }
        return call.getMethod().getReturnType() == int.class ? 1 : null;
    }

    private static String contractBody(String status) {
        return "{\"schemaVersion\":\"1.0\",\"status\":\"" + status
            + "\",\"timestamp\":\"2026-10-01T08:12:20Z\",\"app\":{\"name\":\"Order Engine\"}}";
    }

    private void respond(int statusCode, String body) {
        when(httpClient.fetch(any(), any(), any()))
            .thenReturn(new HealthHttpClient.Response(statusCode, body, 12L, null));
    }

    private void cannotReach(String reason) {
        when(httpClient.fetch(any(), any(), any())).thenThrow(new HealthFetchException(reason));
    }

    @Test
    void up_isOnline_andClearsEarlierFailures() {
        storedFailures.set(1);
        respond(200, contractBody("UP"));
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.ONLINE);
        assertThat(storedFailures.get()).isZero();
    }

    @Test
    void degraded_isStillOnline() {
        respond(200, contractBody("DEGRADED"));
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.ONLINE);
    }

    @Test
    void unreachable_isOfflineOnlyOnTheSecondFailure() {
        cannotReach("Timed out");
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        assertThat(storedFailures.get()).isEqualTo(1);
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.OFFLINE);
        assertThat(storedFailures.get()).isEqualTo(2);
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.OFFLINE);
    }

    @Test
    void oneFailureBetweenSuccesses_neverGoesOffline() {
        respond(200, contractBody("UP"));
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.ONLINE);
        cannotReach("Timed out");
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        respond(200, contractBody("UP"));
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.ONLINE);
        assertThat(storedFailures.get()).isZero();
    }

    @Test
    void applicationReportingDown_countsAsAFailure() {
        respond(200, contractBody("DOWN"));
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.OFFLINE);
    }

    @Test
    void badHttpStatus_countsAsAFailure() {
        respond(500, "oops");
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.OFFLINE);
    }

    @Test
    void bodyThatIsNotValidJson_countsAsAFailure() {
        respond(200, "<html>maintenance page</html>");
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.OFFLINE);
    }

    @Test
    void actuator503WithABody_isReadAsDown_notAsUnreachable() {
        format = "ACTUATOR";
        respond(503, "{\"status\":\"DOWN\"}");
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.OFFLINE);
    }

    @Test
    void contract503_isAFailure_evenWithAValidBody() {
        respond(503, contractBody("UP"));
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.OFFLINE);
    }

    @Test
    void unknown_changesNothing_andKeepsTheFailureCount() {
        storedFailures.set(1);
        respond(200, contractBody("UNKNOWN"));
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.UNCHANGED);
        assertThat(storedFailures.get()).isEqualTo(1);
    }

    @Test
    void withoutAHealthSetup_theSshCheckStaysInCharge() {
        configured = false;
        assertThat(service.check(APP_ID, true)).isEqualTo(HealthDecision.NOT_CONFIGURED);
        verify(httpClient, never()).fetch(any(), any(), any());
    }

    @Test
    void aCheckThatIsNotDueYet_isSkipped() {
        respond(200, contractBody("UP"));
        assertThat(service.check(APP_ID, false)).isEqualTo(HealthDecision.ONLINE);
        assertThat(service.check(APP_ID, false)).isEqualTo(HealthDecision.UNCHANGED);
        verify(httpClient, times(1)).fetch(any(), any(), any());
    }

    @Test
    void aForcedCheck_ignoresTheInterval() {
        respond(200, contractBody("UP"));
        service.check(APP_ID, true);
        service.check(APP_ID, true);
        verify(httpClient, times(2)).fetch(any(), any(), any());
    }

    @Test
    void resettingTheSchedule_makesTheNextTickCheckAgain() {
        respond(200, contractBody("UP"));
        service.check(APP_ID, false);
        service.resetSchedule(APP_ID);
        service.check(APP_ID, false);
        verify(httpClient, times(2)).fetch(any(), any(), any());
    }

    @Test
    void everyCheck_isPushedToTheBrowsers() {
        cannotReach("Timed out");
        service.check(APP_ID, true);
        respond(200, contractBody("UP"));
        service.check(APP_ID, true);
        verify(readService, times(2)).publish(APP_ID);
    }

    @Test
    void aOneOffTest_changesNothing() {
        respond(200, contractBody("UP"));
        HealthCheckService.TestResult result =
            service.test("http://app.test/status", "CONTRACT", null, null, "Order Engine");
        assertThat(result.ok()).isTrue();
        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(storedFailures.get()).isZero();
        verify(readService, never()).publish(any());
    }

    @Test
    void aOneOffTest_reportsWhyItFailed() {
        cannotReach("Connection refused or host unreachable");
        HealthCheckService.TestResult result =
            service.test("http://app.test/status", "CONTRACT", null, null, "Order Engine");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("Connection refused or host unreachable");
        assertThat(result.httpStatus()).isNull();
    }
}
