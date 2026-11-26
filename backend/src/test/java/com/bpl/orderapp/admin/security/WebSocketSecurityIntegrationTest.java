package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** Who may publish on the live stream, and when it is cut off. */
class WebSocketSecurityIntegrationTest {

    @TempDir
    Path dir;

    private TestApp app;
    private String adminCookie;
    private UUID bobId;
    private String bobCookie;

    @BeforeEach
    void boot() throws Exception {
        app = new TestApp(dir);
        bobId = app.createUser("bob", "USER", false);
        app.ctx.getBean(JdbcTemplate.class).update(
            "insert into applications (name, server_ip, ssh_username, ssh_password_enc, "
                + "ssh_host_key_fingerprint, start_script, stop_script, log_script, created_at, updated_at) "
                + "values ('a1', '192.0.2.1', 'u', 'x', 'f', 's', 's', 'l', 0, 0)");
        adminCookie = app.login("admin");
        bobCookie = app.login("bob");
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private long appId() {
        return app.ctx.getBean(JdbcTemplate.class).queryForObject("select id from applications", Long.class);
    }

    private void assignBob() throws Exception {
        assertThat(app.send("PUT", "/api/v1/users/" + bobId, adminCookie,
            "{\"assignedApplicationIds\":[" + appId() + "]}").statusCode()).isEqualTo(200);
    }

    private static void assertClosed(TestApp.Stomp socket) {
        assertThatCode(() -> socket.closed.get(5, TimeUnit.SECONDS))
            .as("socket should be closed by the server").doesNotThrowAnyException();
    }

    @Test
    void aClientCannotPublishToATopicWithAMessageFrame() throws Exception {
        TestApp.Stomp admin = app.stomp(adminCookie);
        admin.subscribe("/topic/audit-log");
        TestApp.Stomp bob = app.stomp(bobCookie);

        bob.send("MESSAGE\ndestination:/topic/audit-log\ncontent-type:application/json\n\n{\"forged\":true}");

        assertThat(admin.frames.poll(2, TimeUnit.SECONDS)).isNull();
        assertClosed(bob);
    }

    @Test
    void deletingAUserClosesTheirOpenSocket() throws Exception {
        TestApp.Stomp bob = app.stomp(bobCookie);

        assertThat(app.send("DELETE", "/api/v1/users/" + bobId, adminCookie, null).statusCode()).isEqualTo(204);

        assertClosed(bob);
    }

    @Test
    void resettingAPasswordClosesTheOpenSocket() throws Exception {
        TestApp.Stomp bob = app.stomp(bobCookie);

        assertThat(app.send("POST", "/api/v1/users/" + bobId + "/reset-password", adminCookie, null)
            .statusCode()).isEqualTo(200);

        assertClosed(bob);
    }

    @Test
    void changingAssignmentsClosesTheSocketButKeepsTheSession() throws Exception {
        assignBob();
        TestApp.Stomp bob = app.stomp(bobCookie);
        bob.subscribe("/topic/application-status/" + appId());

        assertThat(app.send("PUT", "/api/v1/users/" + bobId, adminCookie,
            "{\"assignedApplicationIds\":[]}").statusCode()).isEqualTo(200);

        assertClosed(bob);
        assertThat(app.send("GET", "/api/v1/auth/me", bobCookie, null).statusCode()).isEqualTo(200);
    }

    @Test
    void anUpdateThatChangesNoAccessLeavesTheSocketOpen() throws Exception {
        assignBob();
        TestApp.Stomp bob = app.stomp(bobCookie);

        assertThat(app.send("PUT", "/api/v1/users/" + bobId, adminCookie,
            "{\"fullName\":\"Bob B\",\"assignedApplicationIds\":[" + appId() + "]}").statusCode()).isEqualTo(200);
        Thread.sleep(1000);

        assertThat(bob.closed).isNotDone();
    }

    @Test
    void loggingOutClosesThatSessionsSocketOnly() throws Exception {
        TestApp.Stomp first = app.stomp(bobCookie);
        TestApp.Stomp other = app.stomp(app.login("bob"));

        app.send("POST", "/api/v1/auth/logout", bobCookie, null);

        assertClosed(first);
        assertThat(other.closed).isNotDone();
    }
}
