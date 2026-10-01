package com.bpl.orderapp.admin.health;

import java.time.Instant;
import java.util.List;

/**
 * One application's health at one moment, in the normalized shape of
 * HEALTH_CONTRACT.md. Whatever format an application actually returns
 * (contract or Actuator), the adapters produce this, and this is what
 * gets stored in {@code application_health_latest.payload} and sent to
 * the frontend.
 *
 * <p>Every field except {@code status} and {@code timestamp} is
 * optional. A null component means "the application did not provide
 * it" — never a real zero or an empty string — which is why the
 * numeric fields are boxed ({@code Double}, {@code Long},
 * {@code Boolean}) rather than primitives.
 */
public record HealthSnapshot(
        String schemaVersion,
        HealthStatus status,
        Instant timestamp,
        Instant startedAt,
        App app,
        Build build,
        Resources resources,
        List<Check> checks,
        Traffic traffic,
        Maintenance maintenance,
        List<Job> jobs) {

    public record App(String name, String description, String environment, String version,
                      String owner, String contact, Links links) {}

    public record Links(String app, String docs, String runbook) {}

    public record Build(String commit, String branch, Instant deployedAt) {}

    public record Resources(Cpu cpu, Memory memory, Disk disk) {}

    public record Cpu(Double processPercent, Double systemPercent) {}

    public record Memory(Double usedMb, Double limitMb, Double usedPercent) {}

    public record Disk(Double totalGb, Double freeGb, Double usedPercent) {}

    /** One subsystem or dependency (database, cache, queue, external API...). */
    public record Check(String name, String type, HealthStatus status,
                        Double latencyMs, String message) {}

    /** Request statistics over a recent window. */
    public record Traffic(Double windowMinutes, Long requestCount, Long errorCount,
                          Double avgLatencyMs) {}

    public record Maintenance(Boolean enabled, String message, Instant until) {}

    public record Job(String name, Instant lastRunAt, String lastResult, Instant nextRunAt) {}
}
