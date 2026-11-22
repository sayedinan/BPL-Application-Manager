package com.bpl.orderapp.admin.maintenance;

import com.bpl.orderapp.admin.health.HealthReadService;
import com.bpl.orderapp.admin.status.StatusPollingOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 1440;
    public static final int MAX_DAYS_AHEAD = 30;

    private final ApplicationMaintenanceRepository maintenanceRepository;
    private final StatusPollingOrchestrator statusPolling;
    private final HealthReadService healthReadService;

    public MaintenanceService(ApplicationMaintenanceRepository maintenanceRepository,
            StatusPollingOrchestrator statusPolling, HealthReadService healthReadService) {
        this.maintenanceRepository = maintenanceRepository;
        this.statusPolling = statusPolling;
        this.healthReadService = healthReadService;
    }

    public void start(Long applicationId, Instant startsAt, Instant endsAt, String note, String username) {
        // One window per application: replace it if there already is one.
        ApplicationMaintenance window = maintenanceRepository.findById(applicationId)
            .orElseGet(() -> new ApplicationMaintenance(applicationId));
        window.schedule(startsAt, endsAt, note, username);
        maintenanceRepository.save(window);
        healthReadService.publish(applicationId);
    }

    public boolean end(Long applicationId) {
        Optional<ApplicationMaintenance> found = maintenanceRepository.findById(applicationId);
        if (found.isEmpty()) {
            return false;
        }
        boolean wasOpen = !found.get().getStartsAt().isAfter(Instant.now());
        if (maintenanceRepository.deleteWindow(applicationId) == 0) {
            return false; // someone else closed it a moment ago and handles the follow-up
        }
        if (wasOpen) {
            afterWindow(applicationId);       // it was open: send the held-back alert if still offline
        } else {
            healthReadService.publish(applicationId); // it was only scheduled: just refresh the UI
        }
        return true;
    }

    @Scheduled(fixedDelay = 30000)
    public void closeExpiredWindows() {
        try {
            Instant now = Instant.now();
            for (Long applicationId : maintenanceRepository.findExpiredApplicationIds(now)) {
                // Only the caller that actually removed the row sends the follow-up.
                if (maintenanceRepository.deleteWindowIfEnded(applicationId, now) > 0) {
                    afterWindow(applicationId);
                }
            }
            // Windows that just opened: refresh the UI of everyone watching them.
            maintenanceRepository.findApplicationIdsStartedBetween(now.minusSeconds(60), now)
                .forEach(healthReadService::publish);
        } catch (RuntimeException e) {
            log.warn("Closing expired maintenance windows failed", e);
        }
    }

    private void afterWindow(Long applicationId) {
        try {
            statusPolling.notifyIfStillOffline(applicationId);
        } catch (RuntimeException e) {
            log.warn("Alert after maintenance failed for application id={}", applicationId, e);
        }
        healthReadService.publish(applicationId);
    }
}
