package com.bpl.orderapp.admin.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bpl.orderapp.admin.BplApplicationAdminApplication;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The really running app on a temporary database, plus small HTTP and STOMP clients. */
final class TestApp implements AutoCloseable {

    static final String PASSWORD = "Known-Pass-123!";
    private static final String ORIGIN = "http://localhost:5173";

    private final HttpClient http = HttpClient.newHttpClient();
    final ConfigurableApplicationContext ctx;
    private final String hostAndPort;

    TestApp(Path dir) {
        ctx = new SpringApplicationBuilder(BplApplicationAdminApplication.class).run(
            "--spring.profiles.active=test", "--server.port=0",
            "--spring.datasource.url=jdbc:sqlite:" + dir.resolve("app.db").toAbsolutePath()
                + "?foreign_keys=on&busy_timeout=10000&journal_mode=WAL&transaction_mode=IMMEDIATE",
            "--spring.cors.allowed-origins=" + ORIGIN);
        hostAndPort = "localhost:" + ctx.getEnvironment().getProperty("local.server.port");
        ctx.getBean(JdbcTemplate.class).update(
            "update users set password_hash = ?, must_change_password = 0 where username = 'admin'", hash());
    }

    private String hash() {
        return ctx.getBean(BCryptPasswordEncoder.class).encode(PASSWORD);
    }

    UUID createUser(String username, String role, boolean mustChangePassword) {
        User u = new User();
        u.setUsername(username);
        u.setFullName(username);
        u.setPasswordHash(hash());
        u.setRole(role);
        u.setEmail(username + "@example.com");
        u.setMustChangePassword(mustChangePassword);
        return ctx.getBean(UserRepository.class).save(u).getId();
    }

    /** Returns the session cookie. */
    String login(String username) throws Exception {
        HttpResponse<String> res = send("POST", "/api/v1/auth/login", null,
            "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, res.statusCode(), res.body());
        return res.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    /** As the web app sends it. */
    HttpResponse<String> send(String method, String path, String cookie, String json) throws Exception {
        return send(builder(method, path, cookie, json).header("X-BPL-CSRF", "1"));
    }

    /** As another origin's form or no-preflight fetch would send it. */
    HttpResponse<String> sendWithoutCsrfHeader(String method, String path, String cookie) throws Exception {
        return send(builder(method, path, cookie, null));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder builder(String method, String path, String cookie, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://" + hostAndPort + path))
            .method(method, json == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) b.header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        return b;
    }

    CompletableFuture<Stomp> openSocket(String cookie) {
        Stomp stomp = new Stomp();
        return http.newWebSocketBuilder()
            .header("Cookie", cookie).header("Origin", ORIGIN)
            .buildAsync(URI.create("ws://" + hostAndPort + "/ws"), stomp)
            .thenApply(socket -> {
                stomp.socket = socket;
                return stomp;
            });
    }

    /** A connected STOMP client. */
    Stomp stomp(String cookie) throws Exception {
        Stomp s = openSocket(cookie).get(5, TimeUnit.SECONDS);
        s.send("CONNECT\naccept-version:1.2\nhost:localhost\n\n");
        s.await("CONNECTED");
        return s;
    }

    @Override
    public void close() {
        ctx.close();
    }

    static final class Stomp implements WebSocket.Listener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        void send(String frame) throws Exception {
            socket.sendText(frame + "\0", true).get(5, TimeUnit.SECONDS);
        }

        /** Subscribes and returns once the broker has processed it. */
        void subscribe(String destination) throws Exception {
            send("SUBSCRIBE\nid:sub-0\ndestination:" + destination + "\n\n");
            // The simple broker sends no receipt for SUBSCRIBE.
            Thread.sleep(400);
        }

        String await(String command) throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            if (frame == null || !frame.startsWith(command)) {
                throw new AssertionError("Expected " + command + " but got " + frame);
            }
            return frame;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                if (!text.isBlank()) frames.add(text); // blank = heartbeat
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.complete(null);
        }
    }
}
