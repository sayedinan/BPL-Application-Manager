package com.bpl.orderapp.admin.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bpl.orderapp.admin.BplApplicationAdminApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Changing the role of a user who has a live session, against the real SQLite
 * session store. Session revocation runs in its own transaction, so doing it
 * inside the update transaction cannot get SQLite's single write lock. A mocked
 * session repository (UserControllerTest) cannot catch that.
 */
class UserRoleChangeSessionTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void changingTheRoleOfALoggedInUserSucceedsAndEndsTheirSession() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("role.db").toAbsolutePath()
                + "?foreign_keys=on&busy_timeout=2000&journal_mode=WAL"
                + "&synchronous=NORMAL&transaction_mode=IMMEDIATE";
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BplApplicationAdminApplication.class)
                .run("--spring.profiles.active=test", "--server.port=0", "--spring.datasource.url=" + url,
                        "--spring.cors.allowed-origins=http://localhost:5173")) {
            String base = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            String hash = ctx.getBean(BCryptPasswordEncoder.class).encode("Known-Pass-123!");
            jdbc.update("update users set password_hash = ?, must_change_password = 0 where username = 'admin'", hash);

            User labib = new User();
            labib.setUsername("labib");
            labib.setFullName("Sayed Labib");
            labib.setPasswordHash(hash);
            labib.setRole("ADMIN");
            labib.setEmail("labib@example.com");
            labib.setMustChangePassword(false);
            java.util.UUID id = ctx.getBean(UserRepository.class).save(labib).getId();

            String labibCookie = login(base, "labib");
            String adminCookie = login(base, "admin");

            // CSRF: the token cookie is issued on a GET and echoed back in a header.
            HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me"))
                    .header("Cookie", adminCookie).build(), HttpResponse.BodyHandlers.ofString());
            String xsrf = csrf.headers().allValues("Set-Cookie").stream()
                    .filter(c -> c.startsWith("XSRF-TOKEN="))
                    .map(c -> c.split(";", 2)[0]).findFirst().orElse(null);
            HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(base + "/api/v1/users/" + id))
                    .header("Content-Type", "application/json")
                    .header("Cookie", xsrf == null ? adminCookie : adminCookie + "; " + xsrf)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"role\":\"SYS_ADMIN\"}"));
            if (xsrf != null) put.header("X-XSRF-TOKEN", xsrf.substring("XSRF-TOKEN=".length()));

            HttpResponse<String> update = http.send(put.build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, update.statusCode(), update.body());
            assertEquals("SYS_ADMIN",
                    jdbc.queryForObject("select role from users where username = 'labib'", String.class));
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me"))
                    .header("Cookie", labibCookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private String login(String base, String username) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"Known-Pass-123!\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return res.headers().allValues("Set-Cookie").stream()
                .filter(c -> !c.startsWith("XSRF-TOKEN="))
                .map(c -> c.split(";", 2)[0]).findFirst().orElseThrow();
    }
}
