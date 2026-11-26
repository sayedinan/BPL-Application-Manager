package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class UserGuardrailsIntegrationTest {

    @TempDir
    Path dir;

    private TestApp app;
    private JdbcTemplate jdbc;
    private String sysAdmin;

    @BeforeEach
    void boot() throws Exception {
        app = new TestApp(dir);
        jdbc = app.ctx.getBean(JdbcTemplate.class);
        sysAdmin = app.login("admin");
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private String roleOf(String username) {
        return jdbc.queryForObject("select role from users where username = ?", String.class, username);
    }

    private int count(String username) {
        return jdbc.queryForObject("select count(*) from users where username = ?", Integer.class, username);
    }

    private HttpResponse<String> update(String cookie, UUID id, String json) throws Exception {
        return app.send("PUT", "/api/v1/users/" + id, cookie, json);
    }

    @Test
    void theLastSysAdminCannotBeDemoted() throws Exception {
        UUID adminId = UUID.fromString(
            jdbc.queryForObject("select id from users where username = 'admin'", String.class));

        HttpResponse<String> res = update(sysAdmin, adminId, "{\"role\":\"ADMIN\"}");

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("LAST_SYS_ADMIN");
        assertThat(roleOf("admin")).isEqualTo("SYS_ADMIN");
    }

    @Test
    void aSysAdminCanBeDemotedWhileAnotherRemains() throws Exception {
        UUID second = app.createUser("second", "SYS_ADMIN", false);

        assertThat(update(sysAdmin, second, "{\"role\":\"ADMIN\"}").statusCode()).isEqualTo(200);
        assertThat(roleOf("second")).isEqualTo("ADMIN");
    }

    @Test
    void anAdminCannotManageAdminAccounts() throws Exception {
        UUID peer = app.createUser("peer", "ADMIN", false);
        UUID self = app.createUser("boss", "ADMIN", false);
        String admin = app.login("boss");

        assertThat(app.send("POST", "/api/v1/users/" + peer + "/reset-password", admin, null).statusCode())
            .isEqualTo(403);
        assertThat(app.send("DELETE", "/api/v1/users/" + peer, admin, null).statusCode()).isEqualTo(403);
        assertThat(update(admin, peer, "{\"role\":\"USER\"}").statusCode()).isEqualTo(403);
        assertThat(update(admin, peer, "{\"email\":\"x@example.com\"}").statusCode()).isEqualTo(403);
        assertThat(update(admin, self, "{\"fullName\":\"New\"}").statusCode()).isEqualTo(403);
        assertThat(roleOf("peer")).isEqualTo("ADMIN");
    }

    @Test
    void anAdminStillManagesUsersAndASysAdminManagesAdmins() throws Exception {
        UUID user = app.createUser("worker", "USER", false);
        UUID other = app.createUser("boss", "ADMIN", false);
        String admin = app.login("boss");

        assertThat(app.send("POST", "/api/v1/users/" + user + "/reset-password", admin, null).statusCode())
            .isEqualTo(200);
        assertThat(update(admin, user, "{\"fullName\":\"W\"}").statusCode()).isEqualTo(200);
        assertThat(app.send("DELETE", "/api/v1/users/" + user, admin, null).statusCode()).isEqualTo(204);
        assertThat(app.send("POST", "/api/v1/users/" + other + "/reset-password", sysAdmin, null).statusCode())
            .isEqualTo(200);
    }

    @Test
    void aUserWhoseAssignmentFailsIsNotLeftHalfCreated() throws Exception {
        HttpResponse<String> res = app.send("POST", "/api/v1/users", sysAdmin,
            "{\"username\":\"half\",\"fullName\":\"Half\",\"role\":\"USER\",\"email\":\"h@example.com\","
                + "\"phoneNumber\":\"8801712345678\",\"assignedApplicationIds\":[999999]}");

        assertThat(res.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(count("half")).isZero();
    }

    @Test
    void theConfiguredWebSocketLimitIsApplied() throws Exception {
        var limits = app.ctx.getEnvironment().getProperty("websocket.max-per-user", Integer.class);
        assertThat(limits).isEqualTo(2);
        TestApp.Stomp first = app.stomp(sysAdmin);
        TestApp.Stomp second = app.stomp(sysAdmin);

        TestApp.Stomp third = app.openSocket(sysAdmin).get();
        third.send("CONNECT\naccept-version:1.2\nhost:localhost\n\n");

        third.await("ERROR");
        assertThat(first.closed).isNotDone();
        assertThat(second.closed).isNotDone();
    }
}
