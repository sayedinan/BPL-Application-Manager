package com.bpl.orderapp.admin.health;

import com.bpl.orderapp.admin.common.SshCredentialCipher;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks one application's health endpoint, stores the result, and
 * decides what it means for online/offline (HEALTH-MONITORING.md §3).
 *
 * <p>This class does not schedule anything and does not touch the
 * online/offline streak, audit log or notifications. StatusPollingOrchestrator
 * calls {@link #check} on its existing 3-second tick and acts on the
 * returned {@link HealthDecision}, so there is still exactly one place
 * that records transitions and sends alerts.
 *
 * <p>Each call: reads the application's health config, skips the work
 * if the next check is not due yet, fetches the endpoint, normalizes the
 * response into a {@link HealthSnapshot} (standard contract),
 * upserts {@code application_health_latest} and appends a row to
 * {@code application_health_history}.
 *
 * <p>Decision rules:
 * <ul>
 *   <li>UP or DEGRADED: ONLINE immediately, failure count reset.</li>
 *   <li>UNKNOWN: UNCHANGED, failure count left as it was.</li>
 *   <li>Anything else (no response, bad HTTP status, unusable body, or the
 *       application itself reporting DOWN) is one failure; after
 *       {@value #OFFLINE_AFTER_FAILURES} consecutive failures the decision
 *       is OFFLINE, before that UNCHANGED, so one dropped request never
 *       raises an alert.</li>
 * </ul>
 *
 * <p>The scheduler ticks every 3 seconds, so a configured interval is in
 * effect rounded to the tick: 10 s means a check about every 9 to 12 s.
 */
@Component
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    static final int OFFLINE_AFTER_FAILURES = 2;

    private static final int MAX_FAILURE_COUNT = 1000;
    private static final long DUE_SLACK_MS = 1500;
    private static final int HISTORY_RETENTION_DAYS = 30;

    private final SshCredentialCipher cipher;
    private final HealthHttpClient httpClient;
    private final ContractParser contractParser;
    private final ObjectMapper payloadMapper;
    private final HealthReadService readService;
    private final ApplicationHealthConfigRepository configRepository;
    private final ApplicationHealthLatestRepository latestRepository;
    private final ApplicationHealthHistoryRepository historyRepository;

    private final Map<Long, Instant> lastPolled = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public HealthCheckService(SshCredentialCipher cipher,
            HealthHttpClient httpClient, ContractParser contractParser,
            ObjectMapper mapper, HealthReadService readService,
            ApplicationHealthConfigRepository configRepository,
            ApplicationHealthLatestRepository latestRepository,
            ApplicationHealthHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
        this.latestRepository = latestRepository;
        this.configRepository = configRepository;
        this.cipher = cipher;
        this.httpClient = httpClient;
        this.contractParser = contractParser;
        this.readService = readService;
        // Own copy so omitting null fields doesn't change JSON output elsewhere.
        this.payloadMapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * @param force skip the "is it due yet" check and fetch now; used by
     *              the start/stop confirmation loop, which needs a fresh reading
     */
    public HealthDecision check(Long applicationId, boolean force) {
        java.util.Optional<ApplicationHealthConfig> found =
            configRepository.findByApplicationIdAndEnabledTrue(applicationId);
        if (found.isEmpty()) {
            return HealthDecision.NOT_CONFIGURED;
        }
        ApplicationHealthConfig config = found.get();
        long intervalMs = config.getPollIntervalSeconds() * 1000L;

        Instant now = Instant.now();
        Instant last = lastPolled.get(applicationId);
        if (!force && last != null
                && Duration.between(last, now).toMillis() < intervalMs - DUE_SLACK_MS) {
            return HealthDecision.UNCHANGED;
        }
        // A scheduled tick and a forced check can overlap; the second one
        // simply defers to the one already running.
        if (!inFlight.add(applicationId)) {
            return HealthDecision.UNCHANGED;
        }
        try {
            lastPolled.put(applicationId, now);
            return runCheck(applicationId, config);
        } finally {
            inFlight.remove(applicationId);
        }
    }

    private HealthDecision runCheck(Long applicationId, ApplicationHealthConfig config) {
        Instant checkedAt = Instant.now();
        Integer httpStatus = null;
        Long responseMs = null;
        Instant certNotAfter = null;
        HealthSnapshot snapshot = null;
        String error = null;

        try {
            String apiKey = decryptKey(config.getApiKeyEnc());
            HealthHttpClient.Response response = httpClient.fetch(
                config.getUrl(), apiKey, config.getTlsPinSha256());
            httpStatus = response.statusCode();
            responseMs = response.responseMs();
            certNotAfter = response.certNotAfter();
            snapshot = interpret(response);
        } catch (HealthFetchException | InvalidHealthResponseException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            log.warn("Unexpected error checking health of application id={}", applicationId, e);
            error = "Unexpected error while checking health";
        }

        HealthStatus status = snapshot == null ? null : snapshot.status();
        int previousFailures = previousFailures(applicationId);
        int failures;
        HealthDecision decision;
        if (status == HealthStatus.UP || status == HealthStatus.DEGRADED) {
            failures = 0;
            decision = HealthDecision.ONLINE;
        } else if (status == HealthStatus.UNKNOWN) {
            failures = previousFailures;
            decision = HealthDecision.UNCHANGED;
        } else {
            // No response, bad HTTP status, unusable body, or the
            // application itself reports DOWN: one failed check.
            failures = Math.min(previousFailures + 1, MAX_FAILURE_COUNT);
            decision = failures >= OFFLINE_AFTER_FAILURES
                ? HealthDecision.OFFLINE : HealthDecision.UNCHANGED;
            if (status == HealthStatus.DOWN && error == null) {
                error = "Application reports DOWN";
            }
        }

        log.debug("Health check application id={} status={} failures={} decision={} error={}",
            applicationId, status, failures, decision, error);

        persist(applicationId, checkedAt, httpStatus != null, httpStatus, responseMs,
            status, snapshot, error, certNotAfter, failures);
        // Push the new reading to anyone watching this application.
        readService.publish(applicationId);
        return decision;
    }

    /**
     * Called when an application's health setup was saved, so the next tick
     * checks it right away instead of waiting out the old interval.
     */
    public void resetSchedule(Long applicationId) {
        lastPolled.remove(applicationId);
    }

    /** Outcome of a one-off {@link #test} run. */
    public record TestResult(boolean ok, Integer httpStatus, Long responseMs, HealthStatus status,
                             String error, Instant certNotAfter, HealthSnapshot snapshot) {}

    /**
     * One-off check of a configuration for the "Test" button. Fetches and
     * parses exactly like a real check, but stores nothing and never
     * affects online/offline state. {@code ok} means the response was
     * usable (parsed into a snapshot), whatever status it reported.
     */
    public TestResult test(String url, String apiKey, String tlsPinSha256, String appName) {
        try {
            HealthHttpClient.Response response = httpClient.fetch(url, apiKey, tlsPinSha256);
            try {
                HealthSnapshot snapshot = interpret(response);
                return new TestResult(true, response.statusCode(), response.responseMs(),
                    snapshot.status(), null, response.certNotAfter(), snapshot);
            } catch (InvalidHealthResponseException e) {
                return new TestResult(false, response.statusCode(), response.responseMs(),
                    null, e.getMessage(), response.certNotAfter(), null);
            }
        } catch (HealthFetchException e) {
            return new TestResult(false, null, null, null, e.getMessage(), null, null);
        }
    }

    // Only the standard contract is accepted, and only on HTTP 200.
    // Any other status code is a failed check.
    private HealthSnapshot interpret(HealthHttpClient.Response response) {
        if (response.statusCode() != 200) {
            throw new InvalidHealthResponseException("HTTP " + response.statusCode());
        }
        return contractParser.parse(response.body());
    }

    private String decryptKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(encrypted);
        } catch (RuntimeException e) {
            throw new HealthFetchException("API key could not be decrypted");
        }
    }

    private int previousFailures(Long applicationId) {
        return latestRepository.findConsecutiveFailuresByApplicationId(applicationId).orElse(0);
    }

    // Best-effort: a failed write is logged but must not stop the
    // decision from reaching the status poller.
    private void persist(Long applicationId, Instant checkedAt, boolean reachable,
            Integer httpStatus, Long responseMs, HealthStatus status, HealthSnapshot snapshot,
            String error, Instant certNotAfter, int failures) {
        try {
            String payload = null;
            if (snapshot != null) {
                try {
                    payload = payloadMapper.writeValueAsString(snapshot);
                } catch (JsonProcessingException e) {
                    log.warn("Could not serialize health payload for application id={}", applicationId);
                }
            }
            Integer responseMsValue = responseMs == null
                ? null : (int) Math.min(responseMs, Integer.MAX_VALUE);
            String statusText = status == null ? null : status.name();

            ApplicationHealthLatest latest = latestRepository.findById(applicationId)
                .orElseGet(() -> new ApplicationHealthLatest(applicationId));
            latest.record(checkedAt, reachable, httpStatus, responseMsValue, statusText,
                payload, error, certNotAfter, failures);
            latestRepository.save(latest);
            // The resource readings of this check, kept for the history charts
            // (same choice as the frontend: system CPU, else the process's).
            HealthSnapshot.Resources resources = snapshot == null ? null : snapshot.resources();
            Double cpu = null;
            if (resources != null && resources.cpu() != null) {
                cpu = resources.cpu().systemPercent() != null
                    ? resources.cpu().systemPercent() : resources.cpu().processPercent();
            }
            Double memory = resources == null || resources.memory() == null
                ? null : resources.memory().usedPercent();
            Double disk = resources == null || resources.disk() == null
                ? null : resources.disk().usedPercent();
            historyRepository.save(new ApplicationHealthHistory(
                applicationId, checkedAt, reachable, responseMsValue, statusText, cpu, memory, disk));
        } catch (RuntimeException e) {
            log.warn("Could not store health check result for application id={}", applicationId, e);
        }
    }

    // Retention (HEALTH-MONITORING.md §4): history older than 30 days is
    // deleted once a day, at 03:30 server time.
    @Scheduled(cron = "0 30 3 * * *")
    public void deleteOldHistory() {
        try {
            int deleted = historyRepository.deleteOlderThan(
                Instant.now().minus(Duration.ofDays(HISTORY_RETENTION_DAYS)));
            if (deleted > 0) {
                log.info("Deleted {} health history rows older than {} days", deleted, HISTORY_RETENTION_DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("Health history cleanup failed", e);
        }
    }
}
