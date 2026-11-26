package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A temporary password only buys the right to choose a new one. */
class PasswordChangeRequiredIntegrationTest {

    @TempDir
    Path dir;

    private TestApp app;
    private String cookie;

    @BeforeEach
    void boot() throws Exception {
        app = new TestApp(dir);
        app.createUser("temp", "ADMIN", true);
        cookie = app.login("temp");
    }

    @AfterEach
    void stop() {
        app.close();
    }

    @Test
    void theApiIsLockedUntilThePasswordIsChanged() throws Exception {
        HttpResponse<String> blocked = app.send("GET", "/api/v1/users", cookie, null);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(blocked.body()).contains("PASSWORD_CHANGE_REQUIRED");
        // Path tricks do not get round it.
        assertThat(app.send("GET", "/api/v1/%61pplications", cookie, null).statusCode()).isEqualTo(403);
        assertThat(app.send("GET", "/api/v1/auth/me", cookie, null).statusCode()).isEqualTo(200);

        assertThat(app.send("POST", "/api/v1/auth/change-password", cookie,
            "{\"username\":\"temp\",\"oldPassword\":\"" + TestApp.PASSWORD
                + "\",\"newPassword\":\"Another-Pass-456!\"}").statusCode()).isEqualTo(200);

        assertThat(app.send("GET", "/api/v1/users", cookie, null).statusCode()).isEqualTo(200);
    }

    @Test
    void theLiveStreamCannotBeOpened() {
        assertThatThrownBy(() -> app.openSocket(cookie).get(5, TimeUnit.SECONDS))
            .hasStackTraceContaining("WebSocketHandshakeException");
    }

    @Test
    void loggingOutStillWorks() throws Exception {
        assertThat(app.send("POST", "/api/v1/auth/logout", cookie, null).statusCode()).isEqualTo(204);
    }
}
