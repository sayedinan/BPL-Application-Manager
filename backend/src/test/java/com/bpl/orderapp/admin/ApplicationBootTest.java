package com.bpl.orderapp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Gate G6: the whole application boots against an empty SQLite file (Flyway
 * applies the baseline, the Sys.Admin is seeded), login works over HTTP, a second
 * boot on the same file migrates nothing, and the session created before the
 * restart is still valid afterwards (proves SPRING_SESSION works on SQLite).
 */
@ExtendWith(OutputCaptureExtension.class)
class ApplicationBootTest {

    @TempDir
    Path dir;

    private ConfigurableApplicationContext boot() {
        String url = "jdbc:sqlite:" + dir.resolve("boot.db").toAbsolutePath()
                + "?foreign_keys=on&busy_timeout=10000&journal_mode=WAL"
                + "&synchronous=NORMAL&transaction_mode=IMMEDIATE";
        return new SpringApplicationBuilder(BplApplicationAdminApplication.class)
                .run("--spring.profiles.active=test", "--server.port=0", "--spring.datasource.url=" + url,
                        // defined in the dev/prod profiles, not in the test profile
                        "--spring.cors.allowed-origins=http://localhost:5173",
                        "--management.endpoint.health.show-details=always",
                        // no SMTP credentials in tests; the mail indicator would report DOWN
                        "--management.health.mail.enabled=false");
    }

    private static HttpResponse<String> send(HttpRequest req) throws Exception {
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String base(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    @Test
    void bootsTwiceOnTheSameFileAndKeepsTheSession(CapturedOutput output) throws Exception {
        String cookie;

        try (ConfigurableApplicationContext ctx = boot()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertEquals(2, jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where success = 1", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from users where role = 'SYS_ADMIN'", Integer.class));

            // The seeder only logs a random password; set a known one to log in.
            String hash = ctx.getBean(BCryptPasswordEncoder.class).encode("Known-Pass-123!");
            jdbc.update("update users set password_hash = ?, must_change_password = 0 where username = 'admin'", hash);

            HttpResponse<String> health = send(HttpRequest.newBuilder(URI.create(base(ctx) + "/actuator/health")).build());
            assertEquals(200, health.statusCode(), health.body());

            HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(base(ctx) + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("X-BPL-CSRF", "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"Known-Pass-123!\"}"))
                    .build());
            assertEquals(200, login.statusCode(), login.body());
            cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

            HttpResponse<String> me = send(HttpRequest.newBuilder(URI.create(base(ctx) + "/api/v1/auth/me"))
                    .header("Cookie", cookie).build());
            assertEquals(200, me.statusCode(), me.body());
            assertTrue(jdbc.queryForObject("select count(*) from SPRING_SESSION", Integer.class) >= 1);
        }

        // Second boot on the same file.
        try (ConfigurableApplicationContext ctx = boot()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertEquals(2, jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where success = 1", Integer.class),
                    "second boot must not apply any migration");
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from users where role = 'SYS_ADMIN'", Integer.class),
                    "seeder must not create a second Sys.Admin");
            // Spring's default in-memory user is unused here and must not log a password.
            assertTrue(!output.getAll().contains("Using generated security password"),
                    "a generated Spring password was logged");

            HttpResponse<String> me = send(HttpRequest.newBuilder(URI.create(base(ctx) + "/api/v1/auth/me"))
                    .header("Cookie", cookie).build());
            assertEquals(200, me.statusCode(), "session from before the restart must still be valid: " + me.body());
        }
    }
}
