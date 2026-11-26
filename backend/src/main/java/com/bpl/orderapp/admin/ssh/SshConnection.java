package com.bpl.orderapp.admin.ssh;
import com.jcraft.jsch.*;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SshConnection {
    // Cached session; key = host+":"+user+":"+fingerprint+":"+purpose, value = JSch Session.
    // "purpose" (e.g. "log", "status") keeps the log poller and the status
    // poller on independent connections even when they target the same
    // host/user/fingerprint — each has its own schedule and shouldn't
    // contend on one shared session. Existing call sites that don't pass a
    // purpose keep working unchanged via the "default" overloads below.
    private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        sessionCache.values().forEach(Session::disconnect);
        sessionCache.clear();
    }

    private String cacheKey(String host, String user, String fingerprint, String purpose) {
        return host + ":" + user + ":" + (fingerprint == null ? "" : fingerprint) + ":" + purpose;
    }

    public Session connect(String host, String user, String pass, String fingerprint) throws Exception {
        return connect(host, user, pass, fingerprint, "default");
    }

    public Session connect(String host, String user, String pass, String fingerprint, String purpose) throws Exception {
        // Without a pinned host key, whoever answers on this address would get the saved password.
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new SecurityException("SSH_AUTH_FAILED: no host key fingerprint saved for this server; "
                + "run Test Connection on the application and save it");
        }
        String key = cacheKey(host, user, fingerprint, purpose);
        Session session = sessionCache.get(key);
        if (session != null && session.isConnected()) {
            return session;
        }
        // Serialize per cache key so concurrent callers don't each open a
        // session and leak the loser.
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            session = sessionCache.get(key);
            if (session != null && session.isConnected()) {
                return session;
            }
            Session fresh = openSession(host, user, pass, fingerprint);
            sessionCache.put(key, fresh);
            return fresh;
        }
    }

    /**
     * Opens a one-off, uncached session (e.g. for test-connection, where the
     * supplied credentials must really be exercised). Caller must disconnect.
     */
    public Session openUncached(String host, String user, String pass, String fingerprint) throws Exception {
        return openSession(host, user, pass, fingerprint);
    }

    private Session openSession(String host, String user, String pass, String fingerprint) throws Exception {
        JSch jsch = new JSch();
        boolean pinned = fingerprint != null && !fingerprint.isBlank();
        if (pinned) {
            // Verify the host key DURING key exchange, i.e. before the
            // password is sent. StrictHostKeyChecking=yes plus a repository
            // that only accepts the expected fingerprint makes JSch abort
            // otherwise.
            jsch.setHostKeyRepository(new FingerprintRepository(jsch, fingerprint.trim()));
        }
        Session s = jsch.getSession(user, host, 22);
        s.setPassword(pass);
        s.setConfig("StrictHostKeyChecking", pinned ? "yes" : "no");
        try {
            s.connect(10000);
        } catch (JSchException e) {
            String m = String.valueOf(e.getMessage());
            if (pinned && (m.contains("HostKey") || m.contains("reject"))) {
                throw new SecurityException("SSH_AUTH_FAILED: host key fingerprint mismatch");
            }
            throw e;
        }
        return s;
    }

    private static final class FingerprintRepository implements HostKeyRepository {
        private final JSch jsch;
        private final String expected;
        FingerprintRepository(JSch jsch, String expected) { this.jsch = jsch; this.expected = expected; }
        @Override public int check(String host, byte[] key) {
            try {
                HostKey hk = new HostKey(host, key);
                return expected.equalsIgnoreCase(hk.getFingerPrint(jsch)) ? OK : NOT_INCLUDED;
            } catch (JSchException e) {
                return NOT_INCLUDED;
            }
        }
        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "fingerprint-pin"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    // Only disconnect/remove from cache on genuine connection-level failure
    public void invalidateCache(String host, String user, String fingerprint) {
        invalidateCache(host, user, fingerprint, "default");
    }

    public void invalidateCache(String host, String user, String fingerprint, String purpose) {
        String key = cacheKey(host, user, fingerprint, purpose);
        Session session = sessionCache.get(key);
        if (session != null) {
            session.disconnect();
        }
        sessionCache.remove(key);
    }

    // Cleanup when an application stops / is deleted — called externally.
    // Callers that need to close BOTH the log and status connections for an
    // application must call this once per purpose (see status redesign —
    // stop/delete now has two cached sessions to tear down, not one).
    public void closeConnection(String host, String user, String fingerprint) {
        closeConnection(host, user, fingerprint, "default");
    }

    public void closeConnection(String host, String user, String fingerprint, String purpose) {
        String key = cacheKey(host, user, fingerprint, purpose);
        Session session = sessionCache.remove(key);
        if (session != null) {
            session.disconnect();
        }
    }

    public SshResult runScript(Session session, String script) throws Exception {
        return runScriptWithTimeout(session, script, 120_000);
    }

    /**
     * Runs a script. On timeout the channel is closed and exitCode is -1
     * (never 0), so callers cannot mistake a hung script for success.
     * Output is drained after the channel closes and capped.
     */
    public SshResult runScriptWithTimeout(Session session, String script, int timeoutMs) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        try {
            ch.setCommand(script);
            ch.setInputStream(null);
            ch.setErrStream(System.err);
            java.io.InputStream in = ch.getInputStream();
            ch.connect();
            byte[] tmp = new byte[1024];
            StringBuilder out = new StringBuilder();
            long start = System.currentTimeMillis();
            boolean timedOut = false;
            while (true) {
                drain(in, tmp, out);
                if (ch.isClosed()) {
                    drain(in, tmp, out); // trailing output after close
                    break;
                }
                if (System.currentTimeMillis() - start >= timeoutMs) {
                    timedOut = true;
                    break;
                }
                Thread.sleep(100);
            }
            int exit = timedOut ? -1 : (ch.getExitStatus() >= 0 ? ch.getExitStatus() : -1);
            return new SshResult(out.toString(), exit);
        } finally {
            ch.disconnect();
        }
    }

    private static void drain(java.io.InputStream in, byte[] tmp, StringBuilder out) throws java.io.IOException {
        while (in.available() > 0) {
            int i = in.read(tmp, 0, tmp.length);
            if (i < 0) break;
            if (out.length() < MAX_OUTPUT_CHARS) {
                out.append(new String(tmp, 0, i, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    public static class SshResult {
        public final String stdout;
        public final int exitCode;
        public SshResult(String stdout, int exitCode) {
            this.stdout = stdout;
            this.exitCode = exitCode;
        }
    }
}
