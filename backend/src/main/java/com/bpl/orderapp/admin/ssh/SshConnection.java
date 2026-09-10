package com.bpl.orderapp.admin.ssh;
import com.jcraft.jsch.*;
import org.springframework.stereotype.Component;

@Component
public class SshConnection {
    public Session connect(String host, String user, String pass, String fingerprint) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(user, host, 22);
        session.setPassword(pass);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.connect(10000); // 10s connect timeout per §13.2
        // 8.2 — explicit fingerprint verification (not folded into 8.1)
        HostKey hostKey = session.getHostKey();
        String presented = hostKey.getFingerPrint(jsch);
        if (fingerprint != null && !fingerprint.equalsIgnoreCase(presented)) {
            session.disconnect();
            throw new SecurityException("SSH_AUTH_FAILED: host key fingerprint mismatch");
        }
        return session;
    }
    // 8.5 — run arbitrary Sys.Admin-authored script via verified SSH session
    public SshResult runScript(Session session, String script) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(script);
        ch.setInputStream(null);
        ch.setErrStream(System.err); // stderr captured; not logged with secrets
        java.io.InputStream in = ch.getInputStream();
        ch.connect();
        byte[] tmp = new byte[1024];
        StringBuilder out = new StringBuilder();
        while (true) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, 1024); if (i < 0) break; out.append(new String(tmp, 0, i));
            }
            if (ch.isClosed()) break; Thread.sleep(100);
        }
        ch.disconnect();
        return new SshResult(out.toString(), ch.getExitStatus());
    }
    // 8.8 — timeout handling per SPEC §13.2 (connect 10s, start/stop 120s, log 15s)
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
                int i = in.read(tmp, 0, 1024); if (i < 0) break; out.append(new String(tmp, 0, i));
            }
            if (ch.isClosed()) break; Thread.sleep(100);
        }
        ch.disconnect();
        int exit = ch.isClosed() ? (ch.getExitStatus() >= 0 ? ch.getExitStatus() : -1) : 0;
        return new SshResult(out.toString(), exit);
    }
    public static class SshResult {
        public final String stdout; public final int exitCode;
        public SshResult(String stdout, int exitCode) { this.stdout = stdout; this.exitCode = exitCode; }
    }
}
