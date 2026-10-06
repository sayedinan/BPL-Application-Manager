package com.bpl.orderapp.admin.maintenance;

import com.bpl.orderapp.admin.health.HealthReadService;
import com.bpl.orderapp.admin.status.StatusPollingOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 1440;

    private final JdbcTemplate jdbc;
    private final StatusPollingOrchestrator statusPolling;
    private final HealthReadService healthReadService;

    public MaintenanceService(JdbcTemplate jdbc, StatusPollingOrchestrator statusPolling,
            HealthReadService healthReadService) {
        this.jdbc = jdbc;
        this.statusPolling = statusPolling;
        this.healthReadService = healthReadService;
    }

    public Instant start(Long applicationId, int minutes, String note, String username) {
        Instant endsAt = Instant.now().plus(Duration.ofMinutes(minutes));
        jdbc.update(
            "INSERT INTO application_maintenance (application_id, ends_at, note, started_by, started_at) "
                + "VALUES (?, ?, ?, ?, NOW()) "
                + "ON CONFLICT (application_id) DO UPDATE SET "
                + "ends_at = EXCLUDED.ends_at, note = EXCLUDED.note, "
                + "started_by = EXCLUDED.started_by, started_at = NOW()",
            applicationId, Timestamp.from(endsAt), note, username);
        healthReadService.publish(applicationId);
        return endsAt;
    }

    public boolean end(Long applicationId) {
        int removed = jdbc.update("DELETE FROM application_maintenance WHERE application_id = ?", applicationId);
        if (removed == 0) {
            return false;
        }
        afterWindow(applicationId);
        return true;
    }

    @Scheduled(fixedDelay = 30000)
    public void closeExpiredWindows() {
        try {
            List<Long> expired = jdbc.queryForList(
                "DELETE FROM application_maintenance WHERE ends_at <= NOW() RETURNING application_id",
                Long.class);
            expired.forEach(this::afterWindow);
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
