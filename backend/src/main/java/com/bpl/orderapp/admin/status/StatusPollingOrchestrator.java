package com.bpl.orderapp.admin.status;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.status.ApplicationStatusStreak;
import com.bpl.orderapp.admin.status.ApplicationStatusStreakRepository;
import com.bpl.orderapp.admin.status.ApplicationStatusTransition;
import com.bpl.orderapp.admin.status.ApplicationStatusTransitionRepository;
import com.bpl.orderapp.admin.health.HealthCheckService;
import com.bpl.orderapp.admin.health.HealthDecision;
import com.bpl.orderapp.admin.log.LogPollingOrchestrator;
import com.bpl.orderapp.admin.log.WebSocketBroadcast;
import com.bpl.orderapp.admin.maintenance.ApplicationMaintenanceRepository;
import com.bpl.orderapp.admin.notification.LifecycleEventType;
import com.bpl.orderapp.admin.notification.Notifier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Always-on, two-way status reconciliation poller.
 *
 * <p>Per STATUS-REDESIGN.md: online/offline is derived live from each
 * application's health endpoint (HEALTH-MONITORING.md), polled on a
 * hardcoded interval. No SSH status_script or user-configurable interval.
 *
 * <p>Unlike the log poller, this runs continuously for every
 * application regardless of last-known state — two-way reconciliation
 * requires it, since it must catch both "thought online, actually
 * dead" and "thought offline, actually running" (started manually
 * outside the app).
 */
@Component
public class StatusPollingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StatusPollingOrchestrator.class);

    // Hardcoded per STATUS-REDESIGN.md §2 — health endpoint polling interval.
    private static final int STATUS_POLL_INTERVAL_SECONDS = 3;

    // Health checks wait on the network (up to ~11 s each when an application
    // is down or slow), so they get their own threads: a few dead applications
    // must not make the SSH checks of every other application late.
    private static final int HEALTH_POLL_THREADS = 8;

    private final WebSocketBroadcast broadcast;
    private final LogPollingOrchestrator logPollingOrchestrator;
    private final ThreadPoolTaskScheduler healthScheduler;

    @jakarta.annotation.PreDestroy
    public void shutdownSchedulers() {
        healthScheduler.shutdown();
    }
    private final AuditWriter auditWriter;
    // Spring auto-injects every bean implementing Notifier — empty
    // list if neither email nor SMS is configured, one entry if only
    // one is, two if both are. See NotificationConfig.
    private final List<Notifier> notifiers;
    private final HealthCheckService healthCheckService;
    private final ApplicationRepository applicationRepository;
    private final ApplicationStatusStreakRepository streakRepository;
    private final ApplicationStatusTransitionRepository transitionRepository;
    private final ApplicationMaintenanceRepository maintenanceRepository;

    // Flap detection for external transitions only (web-app-triggered
    // start/stop are deliberate one-off actions, never flapping).
    // 3+ transitions within FLAP_WINDOW marks an application as
    // flapping and suppresses its emails; audit_logs still records
    // every single transition regardless — only the email is
    // suppressed. Flapping is considered resolved once a transition
    // occurs at least FLAP_QUIET_PERIOD after the previous one.
    private static final int FLAP_TRANSITION_THRESHOLD = 3;
    private static final Duration FLAP_WINDOW = Duration.ofMinutes(2);
    private static final Duration FLAP_QUIET_PERIOD = Duration.ofSeconds(60);
    private final Map<Long, Deque<Instant>> transitionHistory = new ConcurrentHashMap<>();
    private final Set<Long> currentlyFlapping = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> activePollers = new ConcurrentHashMap<>();

    // Set while a start/stop script is executing for an application, so
    // this poller's tick skips it rather than racing the in-flight
    // action. Cleared unconditionally when the action finishes (success
    // or failure). Not persisted — a mid-flight action wouldn't survive
    // a backend restart either, so "not paused" is the right default
    // after one.
    private final Set<Long> actionInProgress = ConcurrentHashMap.newKeySet();

    public StatusPollingOrchestrator(
            WebSocketBroadcast broadcast,
            LogPollingOrchestrator logPollingOrchestrator, AuditWriter auditWriter,
            List<Notifier> notifiers, HealthCheckService healthCheckService,
            ApplicationRepository applicationRepository,
            ApplicationStatusStreakRepository streakRepository,
            ApplicationStatusTransitionRepository transitionRepository,
            ApplicationMaintenanceRepository maintenanceRepository) {
        this.maintenanceRepository = maintenanceRepository;
        this.streakRepository = streakRepository;
        this.transitionRepository = transitionRepository;
        this.auditWriter = auditWriter;
        this.broadcast = broadcast;
        this.logPollingOrchestrator = logPollingOrchestrator;
        this.notifiers = notifiers;
        this.healthCheckService = healthCheckService;
        this.applicationRepository = applicationRepository;
        this.healthScheduler = new ThreadPoolTaskScheduler();
        this.healthScheduler.setPoolSize(HEALTH_POLL_THREADS);
        this.healthScheduler.setThreadNamePrefix("health-poller-");
        this.healthScheduler.initialize();
    }

    // Every application gets a poller, always — the "always-on" half of
    // two-way reconciliation (STATUS-REDESIGN.md §2), unlike the log
    // poller which only runs while online.
    @PostConstruct
    public void startPollersForAllApplications() {
        List<Long> ids = applicationRepository.findAllIds();
        for (Long id : ids) {
            ensureStreakRow(id);
            startPolling(id);
        }
    }

    public void startPolling(Long applicationId) {
        stopPolling(applicationId);
        ScheduledFuture<?> future = healthScheduler.scheduleAtFixedRate(
            () -> pollOnce(applicationId),
            Instant.now(),
            Duration.ofSeconds(STATUS_POLL_INTERVAL_SECONDS));
        activePollers.put(applicationId, future);
        log.info("Started status poller for application id={} (interval={}s)", applicationId, STATUS_POLL_INTERVAL_SECONDS);
    }

    public void stopPolling(Long applicationId) {
        ScheduledFuture<?> future = activePollers.remove(applicationId);
        if (future != null) {
            future.cancel(false);
            log.info("Stopped status poller for application id={}", applicationId);
        }
    }

    // Called by ApplicationController immediately before running a
    // start/stop script, so this poller's next tick(s) skip this
    // application until the action completes.
    public void pauseFor(Long applicationId) {
        actionInProgress.add(applicationId);
    }

    public void resumeFor(Long applicationId) {
        actionInProgress.remove(applicationId);
    }

    // Who clicked Start/Stop in this web app, so the resulting online/offline
    // flip can be audited with their name. Set before the script runs, cleared
    // by ApplicationController once confirmation finishes (success, failure or
    // timeout) - deliberately outlives pauseFor/resumeFor, because the flip is
    // detected AFTER the script ends.
    private record PendingAction(String username, String role, boolean expectedOnline) {}
    private final ConcurrentHashMap<Long, PendingAction> pendingActions = new ConcurrentHashMap<>();

    public void expectAction(Long applicationId, String username, String role, boolean expectedOnline) {
        pendingActions.put(applicationId, new PendingAction(username, role, expectedOnline));
    }

    public void clearExpectedAction(Long applicationId) {
        pendingActions.remove(applicationId);
    }

    // One audit row per detected flip. If a web-app action for this
    // application is pending AND its expected state matches what was observed,
    // the flip is attributed to that user. Anything else (terminal, crash,
    // docker restart, reboot) is recorded as system/EXTERNAL.
    private void writeTransitionAudit(Long applicationId, boolean observedOnline, String detectedBy) {
        try {
            String name = applicationRepository.findNameById(applicationId).orElse(null);
            if (name == null) {
                return; // application was deleted while the poller was running
            }
            PendingAction pending = pendingActions.get(applicationId);
            boolean viaWebApp = pending != null && pending.expectedOnline() == observedOnline;
            if (viaWebApp) {
                return; // START_/STOP_APPLICATION already records who did it
            }
            java.util.Map<String, Object> detail = new java.util.HashMap<>();
            detail.put("source", "EXTERNAL");
            detail.put("detectedBy", detectedBy);
            boolean inMaintenance = isInMaintenance(applicationId);
            if (inMaintenance) {
                detail.put("maintenance", true);
            }
            auditWriter.write(observedOnline ? "APPLICATION_ONLINE" : "APPLICATION_OFFLINE",
                "system", "SYSTEM", applicationId, name, null, detail, "SUCCESS");

            // Notification module: this branch only runs for
            // externally-detected transitions (the "!viaWebApp" check
            // above already filtered out dashboard-triggered ones —
            // those are handled separately in ApplicationController,
            // right after their own START_/STOP_APPLICATION audit
            // write). During a maintenance window the flip is recorded
            // above but the alert is held back; MaintenanceService sends it
            // if the application is still offline when the window closes.
            if (inMaintenance) {
                log.info("Application id={} is in a maintenance window, alert held back", applicationId);
                return;
            }
            notifyExternalTransition(applicationId, name, observedOnline);
        } catch (Exception e) {
            log.warn("Audit write failed for status transition (application id={})", applicationId, e);
        }
    }

    // Synchronous, on-demand check. Used by ApplicationController's
    // post-action poll-until-confirmed loop (called repeatedly with a
    // short sleep between calls, rather than waiting for the next
    // scheduled tick) and immediately after application creation to get
    // a real first reading instead of defaulting to offline forever.
    public boolean checkNow(Long applicationId) {
        return runCheck(applicationId, true);
    }

    private boolean isInMaintenance(Long applicationId) {
        return maintenanceRepository.isOpen(applicationId, Instant.now());
    }

    /**
     * Called when a maintenance window closes. If the application is still
     * offline, its alert was held back while the window was open and no
     * later transition will ever trigger it, so it is sent now.
     */
    public void notifyIfStillOffline(Long applicationId) {
        if (isOnline(applicationId)) {
            return;
        }
        applicationRepository.findNameById(applicationId)
            .ifPresent(appName -> notifyExternalTransition(applicationId, appName, false));
    }

    // Cheap lookup — no SSH, just the streak pointer's last-known value.
    // This is what GET /applications reads for the dashboard badge.
    public boolean isOnline(Long applicationId) {
        return streakRepository.isOnline(applicationId);
    }

    private void pollOnce(Long applicationId) {
        if (actionInProgress.contains(applicationId)) {
            return;
        }
        runCheck(applicationId, false);
    }

    // Runs status_script, determines online/offline, and reconciles
    // against the streak pointer — recording a transition + broadcasting
    // only when the value actually changed. Returns the freshly-observed
    // value either way.
    private boolean runCheck(Long applicationId, boolean force) {
        HealthDecision decision = healthCheckService.check(applicationId, force);
        if (decision == HealthDecision.NOT_CONFIGURED || decision == HealthDecision.UNCHANGED) {
            return isOnline(applicationId); // no health endpoint yet, or nothing new: keep last known state
        }
        boolean healthOnline = decision == HealthDecision.ONLINE;
        reconcile(applicationId, healthOnline, "HEALTH_API");
        return healthOnline;
    }

    private void ensureStreakRow(Long applicationId) {
        if (!streakRepository.existsById(applicationId)) {
            streakRepository.save(new ApplicationStatusStreak(applicationId, false, Instant.now()));
        }
    }

    private void reconcile(Long applicationId, boolean observedOnline, String detectedBy) {
        java.util.Optional<ApplicationStatusStreak> existing = streakRepository.findById(applicationId);
        boolean hadStreakRow = existing.isPresent();
        boolean previousOnline = hadStreakRow && existing.get().isOnline();

        if (hadStreakRow && previousOnline == observedOnline) {
            return; // no change — nothing to record or broadcast
        }

        Instant now = Instant.now();
        transitionRepository.save(new ApplicationStatusTransition(applicationId, observedOnline, now));

        if (hadStreakRow) {
            ApplicationStatusStreak streak = existing.get();
            streak.flipTo(observedOnline, now);
            streakRepository.save(streak);
        } else {
            streakRepository.save(new ApplicationStatusStreak(applicationId, observedOnline, now));
        }

        broadcast.broadcastStatus(applicationId, observedOnline, now.toString());
        log.info("Application id={} transitioned to {}", applicationId, observedOnline ? "ONLINE" : "OFFLINE");

        // First-ever reading (no streak row yet) is an initial state, not a change.
        if (hadStreakRow) {
            writeTransitionAudit(applicationId, observedOnline, detectedBy);
        }

        // The log poller's on/off switch is now driven by this derived
        // signal, not a stored status column (STATUS-REDESIGN.md §5).
        // This also naturally handles boot-time resume: a just-started
        // poller's first check on an already-online application reports
        // online != previousOnline(false, the ensureStreakRow default),
        // so this fires without any special "resume on startup" logic —
        // same "no special-casing needed" pattern already used by the
        // log dedup algorithm.
        if (observedOnline) {
            logPollingOrchestrator.onAppOnline(applicationId);
        } else {
            logPollingOrchestrator.stopPolling(applicationId);
        }
    }

    // Best-effort: notification failures must never affect the
    // transition/audit logic above, which is why this has its own
    // try/catch rather than sharing the caller's.
    private void notifyExternalTransition(Long applicationId, String applicationName, boolean observedOnline) {
        if (notifiers.isEmpty()) {
            return; // neither email nor SMS configured — module inactive
        }

        Instant now = Instant.now();
        Deque<Instant> history = transitionHistory.computeIfAbsent(applicationId, k -> new ArrayDeque<>());
        boolean wasFlapping = currentlyFlapping.contains(applicationId);
        Instant previousTransition = history.peekLast();

        history.addLast(now);
        while (!history.isEmpty() && Duration.between(history.peekFirst(), now).compareTo(FLAP_WINDOW) > 0) {
            history.pollFirst();
        }

        if (history.size() >= FLAP_TRANSITION_THRESHOLD) {
            currentlyFlapping.add(applicationId);
            log.warn("Application id={} is flapping ({} transitions in the last {}) — suppressing email (audit log still records this transition)",
                applicationId, history.size(), FLAP_WINDOW);
            return;
        }

        String flapNote = null;
        if (wasFlapping) {
            boolean stabilized = previousTransition != null
                && Duration.between(previousTransition, now).compareTo(FLAP_QUIET_PERIOD) >= 0;
            if (!stabilized) {
                return; // still inside a flap burst, just below the count threshold this instant
            }
            currentlyFlapping.remove(applicationId);
            flapNote = "(This application was flapping between online/offline states recently "
                + "— this reflects its current, now-stabilized state. See the Audit Log for the "
                + "full transition history during that period.)";
        }

        LifecycleEventType eventType = observedOnline
            ? LifecycleEventType.EXTERNAL_ONLINE
            : LifecycleEventType.EXTERNAL_OFFLINE;
        for (Notifier notifier : notifiers) {
            try {
                notifier.notifyLifecycleEvent(applicationId, applicationName, eventType, null, null, flapNote);
            } catch (Exception e) {
                log.warn("Notification dispatch failed ({}) for application id={}",
                    notifier.getClass().getSimpleName(), applicationId, e);
            }
        }
    }
}
