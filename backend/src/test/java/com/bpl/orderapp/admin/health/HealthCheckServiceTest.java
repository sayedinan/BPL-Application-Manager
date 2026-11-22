package com.bpl.orderapp.admin.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
    private final AtomicInteger storedFailures = new AtomicInteger(0);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, this::fakeDatabase);
    private final ApplicationHealthConfigRepository configRepository =
        mock(ApplicationHealthConfigRepository.class, this::fakeConfigs);
    private final ApplicationHealthLatestRepository latestRepository =
        mock(ApplicationHealthLatestRepository.class, this::fakeLatest);
    private final HealthCheckService service = new HealthCheckService(
        jdbc, cipher, httpClient, new ContractParser(mapper), mapper, readService,
        configRepository, latestRepository);

    // Stands in for application_health_latest: remembers the failure counter
    // that save() receives and hands it back on the next check.
    private Object fakeLatest(InvocationOnMock call) {
        switch (call.getMethod().getName()) {
            case "findConsecutiveFailuresByApplicationId":
                return java.util.Optional.of(storedFailures.get());
            case "findById":
                return java.util.Optional.empty();
            case "save":
                ApplicationHealthLatest row = call.getArgument(0);
                storedFailures.set(row.getConsecutiveFailures());
                return row;
            default:
                return null;
        }
    }

    private Object fakeConfigs(InvocationOnMock call) {
        if (call.getMethod().getName().equals("findByApplicationIdAndEnabledTrue")) {
            if (!configured) {
                return java.util.Optional.empty();
            }
            ApplicationHealthConfig config = new ApplicationHealthConfig(APP_ID);
            config.apply(true, "http://app.test/status", null, 10);
            return java.util.Optional.of(config);
        }
        return null;
    }

    private Object fakeDatabase(InvocationOnMock call) {
        String sql = call.getArgument(0);
        return call.getMethod().getReturnType() == int.class ? 1 : null;
    }

    private static String contractBody(String status) {
        return "{\"schemaVersion\":\"1.0\",\"status\":\"" + status
            + "\",\"timestamp\":\"2026-10-01T08:12:20Z\",\"app\":{\"name\":\"Order Engine\"}}";
    }

    private void respond(int statusCode, String body) {
        doReturn(new HealthHttpClient.Response(statusCode, body, 12L, null))
            .when(httpClient).fetch(any(), any(), any());
    }

    private void cannotReach(String reason) {
        doThrow(new HealthFetchException(reason))
            .when(httpClient).fetch(any(), any(), any());
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
            service.test("http://app.test/status", null, null, "Order Engine");
        assertThat(result.ok()).isTrue();
        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(storedFailures.get()).isZero();
        verify(readService, never()).publish(any());
    }

    @Test
    void aOneOffTest_reportsWhyItFailed() {
        cannotReach("Connection refused or host unreachable");
        HealthCheckService.TestResult result =
            service.test("http://app.test/status", null, null, "Order Engine");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("Connection refused or host unreachable");
        assertThat(result.httpStatus()).isNull();
    }
}
