package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.bpl.orderapp.admin.BplApplicationAdminApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Rate limits and browser security headers, checked on the really running app. */
class SecurityHardeningIntegrationTest {

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newHttpClient();
    private ConfigurableApplicationContext ctx;

    private void boot(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=test", "--server.port=0",
                "--spring.datasource.url=jdbc:sqlite:" + dir.resolve("sec.db").toAbsolutePath()
                        + "?foreign_keys=on&busy_timeout=10000&journal_mode=WAL&transaction_mode=IMMEDIATE",
                "--spring.cors.allowed-origins=http://localhost:5173"));
        args.addAll(List.of(extra));
        ctx = new SpringApplicationBuilder(BplApplicationAdminApplication.class).run(args.toArray(String[]::new));
    }

    @AfterEach
    void stop() {
        if (ctx != null) ctx.close();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(
                "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String path, String username, String forwardedFor) throws Exception {
        HttpRequest.Builder b = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"wrong-Password-1\"}"));
        if (forwardedFor != null) b.header("X-Forwarded-For", forwardedFor);
        return send(b);
    }

    @Test
    void responsesCarryBrowserSecurityHeaders() throws Exception {
        boot();
        HttpResponse<String> res = send(request("/api/v1/auth/me"));

        assertThat(res.headers().firstValue("Content-Security-Policy")).hasValue(SecurityConfig.CONTENT_SECURITY_POLICY);
        assertThat(res.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(res.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(res.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(res.headers().firstValue("Permissions-Policy")).isPresent();
    }

    /** The page itself is served through Caddy, so Caddy must send the same policy. */
    @Test
    void caddySendsTheSamePolicyAsTheBackend() throws IOException {
        String caddyfile = Files.readString(Path.of("..", "Caddyfile"));

        assertThat(caddyfile).contains("Content-Security-Policy \"" + SecurityConfig.CONTENT_SECURITY_POLICY + "\"");
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY)
                .contains("script-src 'self';", "frame-ancestors 'none'", "object-src 'none'")
                .doesNotContain("unsafe-eval", "wss:", "*");
    }

    @Test
    void repeatedWrongPasswordsAreBlockedWithRetryAfter() throws Exception {
        boot("--rate-limit.login-per-second=1000");
        for (int i = 0; i < 10; i++) {
            assertThat(login("/api/v1/auth/login", "admin", null).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> blocked = login("/api/v1/auth/login", "ADMIN", null);

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("RATE_LIMITED");
        assertThat(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow())).isPositive();
        // Another account from the same address is not caught up in it.
        assertThat(login("/api/v1/auth/login", "someone-else", null).statusCode()).isEqualTo(401);
    }

    @Test
    void loginBurstFromOneAddressIsLimited_evenWithAnEncodedPathOrSpoofedHeader() throws Exception {
        boot("--rate-limit.login-per-second=2");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // %6C is "l": same endpoint. The header changes on every call.
            statuses.add(login("/api/v1/auth/%6Cogin", "user" + i, "10.0.0." + i).statusCode());
        }

        assertThat(statuses).contains(429);
    }

    @Test
    void behindATrustedProxyEachClientAddressHasItsOwnLimit() throws Exception {
        boot("--rate-limit.api-per-ip-per-minute=3", "--rate-limit.trust-forwarded-for=true");
        for (int i = 0; i < 3; i++) {
            assertThat(send(request("/api/v1/auth/me").header("X-Forwarded-For", "203.0.113.1")).statusCode())
                    .isEqualTo(401);
        }

        // Only the last hop (added by the proxy) counts; the client controls the rest.
        assertThat(send(request("/api/v1/auth/me").header("X-Forwarded-For", "1.2.3.4, 203.0.113.1")).statusCode())
                .isEqualTo(429);
        assertThat(send(request("/api/v1/auth/me").header("X-Forwarded-For", "203.0.113.2")).statusCode())
                .isEqualTo(401);
        assertThat(send(request("/actuator/health").header("X-Forwarded-For", "203.0.113.1")).statusCode())
                .isNotEqualTo(429);
    }
}
