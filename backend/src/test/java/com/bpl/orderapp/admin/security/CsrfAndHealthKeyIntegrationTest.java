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

class CsrfAndHealthKeyIntegrationTest {

    @TempDir
    Path dir;

    private TestApp app;
    private JdbcTemplate jdbc;
    private String cookie;

    @BeforeEach
    void boot() throws Exception {
        app = new TestApp(dir);
        jdbc = app.ctx.getBean(JdbcTemplate.class);
        cookie = app.login("admin");
    }

    @AfterEach
    void stop() {
        app.close();
    }

    // ---- CSRF: a page on another origin cannot add a custom header without CORS approval ----

    @Test
    void aStateChangingRequestWithoutTheCsrfHeaderIsRefusedAndChangesNothing() throws Exception {
        UUID bob = app.createUser("bob", "USER", false);

        HttpResponse<String> res = app.sendWithoutCsrfHeader(
            "POST", "/api/v1/users/" + bob + "/reset-password", cookie);

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("CSRF_REJECTED");
        assertThat(jdbc.queryForObject(
            "select must_change_password from users where username = 'bob'", Integer.class)).isZero();
        assertThat(app.sendWithoutCsrfHeader("DELETE", "/api/v1/users/" + bob, cookie).statusCode())
            .isEqualTo(403);
        assertThat(app.sendWithoutCsrfHeader("POST", "/api/v1/auth/logout", cookie).statusCode())
            .isEqualTo(403);
    }

    @Test
    void readingNeedsNoCsrfHeader() throws Exception {
        assertThat(app.sendWithoutCsrfHeader("GET", "/api/v1/users", cookie).statusCode()).isEqualTo(200);
    }

    // ---- The saved health API key only ever goes to the origin it was saved for ----

    private long healthConfigWithKey() throws Exception {
        jdbc.update("insert into applications (name, server_ip, ssh_username, ssh_password_enc, "
            + "ssh_host_key_fingerprint, start_script, stop_script, log_script, created_at, updated_at) "
            + "values ('a1', '192.0.2.1', 'u', 'x', 'f', 's', 's', 'l', 0, 0)");
        long id = jdbc.queryForObject("select id from applications", Long.class);
        assertThat(saveHealth(id, "http://192.0.2.1:9000/health", "\"secret-key\"").statusCode()).isEqualTo(200);
        return id;
    }

    private static String healthBody(String url, String apiKeyJson) {
        return "{\"enabled\":false,\"url\":\"" + url + "\""
            + (apiKeyJson == null ? "" : ",\"apiKey\":" + apiKeyJson) + "}";
    }

    private HttpResponse<String> saveHealth(long id, String url, String apiKeyJson) throws Exception {
        return app.send("PUT", "/api/v1/applications/" + id + "/health/config", cookie, healthBody(url, apiKeyJson));
    }

    @Test
    void theSavedKeyIsNotCarriedToAnotherOrigin() throws Exception {
        long id = healthConfigWithKey();

        for (String other : new String[] {"http://192.0.2.2:9000/health", "http://192.0.2.1:9001/health",
                "https://192.0.2.1:9000/health"}) {
            HttpResponse<String> save = saveHealth(id, other, null);
            assertThat(save.statusCode()).as(other).isEqualTo(400);
            assertThat(save.body()).contains("API key");
            HttpResponse<String> test = app.send("POST", "/api/v1/applications/" + id + "/health/test",
                cookie, healthBody(other, null));
            assertThat(test.statusCode()).as(other).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("select url from application_health_config", String.class))
            .isEqualTo("http://192.0.2.1:9000/health");
    }

    @Test
    void theKeyStaysForTheSameOriginAndAnotherOriginWorksOnceTheKeyIsGiven() throws Exception {
        long id = healthConfigWithKey();

        assertThat(saveHealth(id, "HTTP://192.0.2.1:9000/other-path", null).statusCode()).isEqualTo(200);
        assertThat(saveHealth(id, "http://192.0.2.2:9000/health", "\"new-key\"").statusCode()).isEqualTo(200);
        assertThat(saveHealth(id, "http://192.0.2.3:9000/health", "\"\"").statusCode()).isEqualTo(200);
        // No key left: the URL can change freely.
        assertThat(saveHealth(id, "http://192.0.2.4:9000/health", null).statusCode()).isEqualTo(200);
    }
}
