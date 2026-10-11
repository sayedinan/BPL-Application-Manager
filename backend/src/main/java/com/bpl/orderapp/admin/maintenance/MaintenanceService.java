package com.bpl.orderapp.admin.maintenance;

import com.bpl.orderapp.admin.health.HealthReadService;
import com.bpl.orderapp.admin.status.StatusPollingOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 1440;
    public static final int MAX_DAYS_AHEAD = 30;

    private final JdbcTemplate jdbc;
    private final StatusPollingOrchestrator statusPolling;
    private final HealthReadService healthReadService;

    public MaintenanceService(JdbcTemplate jdbc, StatusPollingOrchestrator statusPolling,
            HealthReadService healthReadService) {
        this.jdbc = jdbc;
        this.statusPolling = statusPolling;
        this.healthReadService = healthReadService;
    }

    public void start(Long applicationId, Instant startsAt, Instant endsAt, String note, String username) {
        jdbc.update(
            "INSERT INTO application_maintenance (application_id, starts_at, ends_at, note, started_by, started_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW()) "
                + "ON CONFLICT (application_id) DO UPDATE SET "
                + "starts_at = EXCLUDED.starts_at, ends_at = EXCLUDED.ends_at, note = EXCLUDED.note, "
                + "started_by = EXCLUDED.started_by, started_at = NOW()",
            applicationId, Timestamp.from(startsAt), Timestamp.from(endsAt), note, username);
        healthReadService.publish(applicationId);
    }

    public boolean end(Long applicationId) {
        List<Boolean> removed = jdbc.queryForList(
            "DELETE FROM application_maintenance WHERE application_id = ? RETURNING (starts_at <= NOW())",
            Boolean.class, applicationId);
        if (removed.isEmpty()) {
            return false;
        }
        if (removed.get(0)) {
            afterWindow(applicationId);       // it was open: send the held-back alert if still offline
        } else {
            healthReadService.publish(applicationId); // it was only scheduled: just refresh the UI
        }
        return true;
    }

    @Scheduled(fixedDelay = 30000)
    public void closeExpiredWindows() {
        try {
            List<Long> expired = jdbc.queryForList(
                "DELETE FROM application_maintenance WHERE ends_at <= NOW() RETURNING application_id",
                Long.class);
            expired.forEach(this::afterWindow);
            List<Long> started = jdbc.queryForList(
                "SELECT application_id FROM application_maintenance "
                    + "WHERE starts_at <= NOW() AND starts_at > NOW() - INTERVAL '60 seconds'",
                Long.class);
            started.forEach(healthReadService::publish);
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
