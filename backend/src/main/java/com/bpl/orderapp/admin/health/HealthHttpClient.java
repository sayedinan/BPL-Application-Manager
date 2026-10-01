package com.bpl.orderapp.admin.health;

import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches an application's health endpoint over HTTP or HTTPS
 * (HEALTH-MONITORING.md).
 *
 * <p>It only fetches: it returns the status code and body as received
 * and leaves the meaning to the caller. A 503 is therefore returned,
 * not thrown, because Spring Boot Actuator answers 503 with a full
 * JSON body when the application is DOWN. Anything that stops the
 * response from arriving at all is thrown as {@link HealthFetchException}.
 *
 * <p>Safety limits: 3 s to connect, 5 s for the response headers, 3 s
 * to read the body, and at most 256 KB of body. Redirects are never
 * followed, so the API key can't be sent to a different host.
 *
 * <p>TLS has exactly two modes (there is deliberately no "skip
 * verification" mode, HEALTH-MONITORING.md D6):
 * <ul>
 *   <li>no pin: normal verification against the JVM's trusted CAs,
 *       including host name checking;</li>
 *   <li>pin: the application's certificate must have exactly the given
 *       SHA-256 fingerprint. Used for self-signed certificates. Host
 *       name and CA chain are not checked, because the fingerprint
 *       already identifies the one certificate we accept.</li>
 * </ul>
 * The certificate's expiry date is returned for HTTPS calls
 * (HEALTH-MONITORING.md D7). A pinned certificate is accepted even if
 * it has expired, so an expired certificate shows up as an expiry
 * warning on the card instead of a false "application down".
 *
 * <p>The API key is only ever sent as the {@code X-API-Key} header and
 * is never logged or included in an exception message.
 */
@Component
public class HealthHttpClient {

    static final String API_KEY_HEADER = "X-API-Key";

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int BODY_TIMEOUT_MS = 3000;
    private static final int MAX_BODY_BYTES = 256 * 1024;

    // Reads response bodies on a separate thread so a server that sends
    // headers and then stalls can't hold a poller thread forever.
    private static final ExecutorService BODY_READERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "health-body-reader");
        t.setDaemon(true);
        return t;
    });

    // One HttpClient per distinct pin ("" = no pin). Clients are
    // thread-safe and pool connections, so they are built once.
    private final ConcurrentHashMap<String, HttpClient> clients = new ConcurrentHashMap<>();

    /**
     * @param url          full health URL, http:// or https://
     * @param apiKey       plain-text API key to send, or null/blank for none
     * @param tlsPinSha256 lowercase hex SHA-256 of the certificate to
     *                     accept, or null for normal verification
     * @throws HealthFetchException if no response could be obtained
     */
    public Response fetch(String url, String apiKey, String tlsPinSha256) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new HealthFetchException("Invalid URL");
        }
        String scheme = uri.getScheme();
        boolean schemeOk = scheme != null
            && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        if (!schemeOk || uri.getHost() == null) {
            throw new HealthFetchException("URL must be http:// or https:// with a host name");
        }

        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Accept", "application/json")
                .header("User-Agent", "BPL-Application-Manager-HealthPoller");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header(API_KEY_HEADER, apiKey);
            }
            request = builder.build();
        } catch (IllegalArgumentException e) {
            throw new HealthFetchException("Request could not be built (check the URL and API key)");
        }

        String clientKey = tlsPinSha256 == null ? "" : tlsPinSha256.toLowerCase(Locale.ROOT);
        HttpClient client = clients.computeIfAbsent(
            clientKey, k -> buildClient(k.isEmpty() ? null : k));

        long startNanos = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HealthFetchException("Interrupted");
        } catch (IOException e) {
            throw translate(e);
        }
        String body = readBody(response.body());
        long responseMs = (System.nanoTime() - startNanos) / 1_000_000L;
        return new Response(response.statusCode(), body, responseMs, certificateExpiry(response));
    }

    /** What came back: status code, body text, total time, and certificate expiry (HTTPS only, else null). */
    public record Response(int statusCode, String body, long responseMs, Instant certNotAfter) {}

    // ------------------------------------------------------------------

    private static HttpClient buildClient(String pinSha256) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        if (pinSha256 != null) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {new PinnedTrustManager(pinSha256)}, null);
                builder.sslContext(context);
            } catch (GeneralSecurityException e) {
                throw new HealthFetchException("TLS setup failed");
            }
        }
        return builder.build();
    }

    private static String readBody(InputStream in) {
        Future<byte[]> task = BODY_READERS.submit(() -> in.readNBytes(MAX_BODY_BYTES + 1));
        byte[] data;
        try {
            data = task.get(BODY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            closeQuietly(in);
            throw new HealthFetchException("Timed out reading the response");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(in);
            throw new HealthFetchException("Interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            closeQuietly(in);
            throw new HealthFetchException("Failed while reading the response");
        }
        closeQuietly(in);
        if (data.length > MAX_BODY_BYTES) {
            throw new HealthFetchException("Response is larger than " + (MAX_BODY_BYTES / 1024) + " KB");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static Instant certificateExpiry(HttpResponse<?> response) {
        Optional<SSLSession> session = response.sslSession();
        if (session.isEmpty()) {
            return null; // plain HTTP
        }
        try {
            Certificate[] certificates = session.get().getPeerCertificates();
            if (certificates.length > 0 && certificates[0] instanceof X509Certificate x509) {
                return x509.getNotAfter().toInstant();
            }
        } catch (SSLPeerUnverifiedException e) {
            // no peer certificate available
        }
        return null;
    }

    private static HealthFetchException translate(IOException e) {
        if (e instanceof HttpTimeoutException) {
            return new HealthFetchException("Timed out");
        }
        if (hasCause(e, UnknownHostException.class)) {
            return new HealthFetchException("Host name not found");
        }
        if (hasCause(e, SSLException.class)) {
            return new HealthFetchException(
                "TLS handshake failed (certificate not trusted, or does not match the pin)");
        }
        if (hasCause(e, ConnectException.class)) {
            return new HealthFetchException("Connection refused or host unreachable");
        }
        return new HealthFetchException("Request failed (" + e.getClass().getSimpleName() + ")");
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Accepts exactly one server certificate, identified by the SHA-256
     * of its encoded form. Extends X509ExtendedTrustManager on purpose:
     * for a plain X509TrustManager the JDK would add its own host name
     * check, which a self-signed certificate reached by IP address can
     * never pass.
     */
    private static final class PinnedTrustManager extends X509ExtendedTrustManager {

        private final String expectedHex;

        PinnedTrustManager(String expectedHex) {
            this.expectedHex = expectedHex;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificates are not accepted");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("Client certificates are not accepted");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("Client certificates are not accepted");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("No certificate presented");
            }
            String actualHex = sha256Hex(chain[0].getEncoded());
            boolean matches = MessageDigest.isEqual(
                actualHex.getBytes(StandardCharsets.UTF_8),
                expectedHex.getBytes(StandardCharsets.UTF_8));
            if (!matches) {
                throw new CertificateException("Certificate does not match the pinned fingerprint");
            }
        }

        private static String sha256Hex(byte[] data) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }
    }
}
