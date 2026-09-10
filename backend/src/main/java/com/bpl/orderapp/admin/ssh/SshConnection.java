package com.bpl.orderapp.admin.ssh;
import com.jcraft.jsch.*;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SshConnection {
    // Per-engine cached session; key = host+":"+user+":"+fingerprint hash, value = JSch Session
    private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();

    private String cacheKey(String host, String user, String fingerprint) {
        return host + ":" + user + ":" + (fingerprint == null ? "" : fingerprint);
    }

    public Session connect(String host, String user, String pass, String fingerprint) throws Exception {
        String key = cacheKey(host, user, fingerprint);
        Session session = sessionCache.get(key);
        if (session != null && session.isConnected()) {
            return session;
        }
        // Connection missing or dead — open fresh, cache it
        JSch jsch = new JSch();
        Session newSession = jsch.getSession(user, host, 22);
        newSession.setPassword(pass);
        newSession.setConfig("StrictHostKeyChecking", "no");
        newSession.connect(10000);
        HostKey hostKey = newSession.getHostKey();
        String presented = hostKey.getFingerPrint(jsch);
        if (fingerprint != null && !fingerprint.equalsIgnoreCase(presented)) {
            newSession.disconnect();
            throw new SecurityException("SSH_AUTH_FAILED: host key fingerprint mismatch");
        }
        sessionCache.put(key, newSession);
        return newSession;
    }

    // Only disconnect/remove from cache on genuine connection-level failure
    public void invalidateCache(String host, String user, String fingerprint) {
        String key = cacheKey(host, user, fingerprint);
        Session session = sessionCache.get(key);
        if (session != null) {
            session.disconnect();
        }
        sessionCache.remove(key);
    }

    // Cleanup when engine stops / deleted — called externally if needed
    public void closeConnection(String host, String user, String fingerprint) {
        String key = cacheKey(host, user, fingerprint);
        Session session = sessionCache.remove(key);
        if (session != null) {
            session.disconnect();
        }
    }

    public SshResult runScript(Session session, String script) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(script);
        ch.setInputStream(null);
        ch.setErrStream(System.err);
        java.io.InputStream in = ch.getInputStream();
        ch.connect();
        byte[] tmp = new byte[1024];
        StringBuilder out = new StringBuilder();
        while (true) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, 1024);
                if (i < 0) break;
                out.append(new String(tmp, 0, i));
            }
            if (ch.isClosed()) break;
            Thread.sleep(100);
        }
        ch.disconnect();
        return new SshResult(out.toString(), ch.getExitStatus());
    }

    public SshResult runScriptWithTimeout(Session session, String script, int timeoutMs) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(script);
        ch.setInputStream(null);
        ch.setErrStream(System.err);
        ch.connect();
        java.io.InputStream in = ch.getInputStream();
        byte[] tmp = new byte[1024];
        StringBuilder out = new StringBuilder();
        long start = System.currentTimeMillis();
        while (!ch.isClosed() && (System.currentTimeMillis() - start) < timeoutMs) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, 1024);
                if (i < 0) break;
                out.append(new String(tmp, 0, i));
            }
            if (ch.isClosed()) break;
            Thread.sleep(100);
        }
        ch.disconnect();
        int exit = ch.isClosed() ? (ch.getExitStatus() >= 0 ? ch.getExitStatus() : -1) : 0;
        return new SshResult(out.toString(), exit);
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
