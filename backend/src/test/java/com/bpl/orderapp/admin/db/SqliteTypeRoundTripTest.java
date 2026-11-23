package com.bpl.orderapp.admin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bpl.orderapp.admin.application.Application;
import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.audit.AuditLog;
import com.bpl.orderapp.admin.audit.AuditLogRepository;
import com.bpl.orderapp.admin.health.ApplicationHealthLatest;
import com.bpl.orderapp.admin.health.ApplicationHealthLatestRepository;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserApplicationAssignment;
import com.bpl.orderapp.admin.user.UserApplicationAssignmentRepository;
import com.bpl.orderapp.admin.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gate G3: UUID, Instant, boolean, inet-as-text and JSON round-trip through
 * Hibernate on the real Flyway baseline, and are stored with the SQLite
 * storage classes the plan assumes (text / integer epoch-ms).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SqliteTypeRoundTripTest {

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ApplicationRepository applications;
    @Autowired AuditLogRepository audits;
    @Autowired ApplicationHealthLatestRepository healthLatest;
    @Autowired UserApplicationAssignmentRepository assignments;

    private void reload() {
        em.flush();
        em.clear();
    }

    private User newUser(String username, String email, String phone) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash("hash");
        u.setRole("USER");
        u.setEmail(email);
        u.setPhoneNumber(phone);
        return u;
    }

    private Application newApp(String name, String ip) throws Exception {
        Application a = new Application();
        a.setName(name);
        a.setServerIp(InetAddress.getByName(ip));
        a.setSshUsername("u");
        a.setSshPasswordEnc("enc");
        a.setSshHostKeyFingerprint("fp");
        a.setStartScript("s");
        a.setStopScript("x");
        a.setLogScript("l");
        return a;
    }

    @Test
    void userRoundTripAndStorageClasses() {
        User saved = users.save(newUser("alice", "a@example.com", "8801712345678"));
        UUID id = saved.getId();
        Instant createdAt = saved.getCreatedAt();
        reload();

        User back = users.findById(id).orElseThrow();
        assertEquals(id, back.getId());
        assertEquals(createdAt.truncatedTo(ChronoUnit.MILLIS), back.getCreatedAt());
        assertTrue(back.isMustChangePassword());

        Map<String, Object> row = jdbc.queryForMap(
                "select typeof(id) a, typeof(created_at) b, typeof(must_change_password) c from users where id = ?",
                id.toString());
        assertEquals("text", row.get("a"));
        assertEquals("integer", row.get("b"));
        assertEquals("integer", row.get("c"));
    }

    @Test
    void applicationIpAndAuditJsonRoundTrip() throws Exception {
        Application app = applications.save(newApp("app-one", "10.1.2.3"));
        AuditLog log = audits.save(new AuditLog(
                Instant.parse("2026-01-02T03:04:05.678Z"), "alice", "ADMIN", "CREATE_APPLICATION",
                app.getId(), "app-one", null,
                Map.of("serverIp", "10.1.2.3", "nested", Map.of("k", 1)), "SUCCESS", "127.0.0.1"));
        Long appId = app.getId();
        Long logId = log.getId();
        reload();

        assertEquals("10.1.2.3", applications.findById(appId).orElseThrow().getServerIp().getHostAddress());
        assertEquals("text", jdbc.queryForObject("select typeof(server_ip) from applications where id = ?", String.class, appId));

        AuditLog back = em.find(AuditLog.class, logId);
        assertEquals(Instant.parse("2026-01-02T03:04:05.678Z"), back.getTimestamp());
        assertEquals("10.1.2.3", back.getDetail().get("serverIp"));
        assertEquals(Map.of("k", 1), back.getDetail().get("nested"));
        assertEquals(1, jdbc.queryForObject("select json_valid(detail) from audit_logs where id = ?", Integer.class, logId));
    }

    @Test
    void healthPayloadRoundTrip() throws Exception {
        Application app = applications.save(newApp("app-two", "10.1.2.4"));
        ApplicationHealthLatest h = new ApplicationHealthLatest(app.getId());
        String payload = "{\"status\":\"UP\",\"cpu\":12.5}";
        h.record(Instant.parse("2026-01-02T03:04:05.678Z"), true, 200, 15, "UP", payload, null, null, 0);
        healthLatest.save(h);
        Long appId = app.getId();
        reload();

        ApplicationHealthLatest back = healthLatest.findById(appId).orElseThrow();
        assertEquals(payload, back.getPayload());
        assertTrue(back.isReachable());
        assertEquals(1, jdbc.queryForObject(
                "select json_valid(payload) from application_health_latest where application_id = ?",
                Integer.class, appId));
    }

    @Test
    void assignmentEmbeddedIdAndNativeQueries() throws Exception {
        User u = users.save(newUser("bob", "b@example.com", "8801712345679"));
        Application app = applications.save(newApp("app-three", "10.1.2.5"));
        assignments.save(new UserApplicationAssignment(u.getId(), app.getId()));
        UUID userId = u.getId();
        Long appId = app.getId();
        reload();

        assertEquals(List.of(appId), assignments.findApplicationIdsByUserId(userId));
        assertEquals(List.of(appId), users.findAssignedApplicationIds("bob"));
        assertEquals(List.of("b@example.com"), users.findAssignedUserEmails(appId));
        assertEquals(List.of("8801712345679"), users.findAssignedUserPhoneNumbers(appId));
    }
}
