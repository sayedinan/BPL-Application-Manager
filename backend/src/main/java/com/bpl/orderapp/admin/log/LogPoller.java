package com.bpl.orderapp.admin.log;
import com.bpl.orderapp.admin.ssh.SshConnection;
import com.bpl.orderapp.admin.ssh.SshConnection.SshResult;
import org.springframework.jdbc.core.JdbcTemplate;
public class LogPoller {
    private final JdbcTemplate jdbc;
    public LogPoller(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public String runLogScript(String script) throws Exception {
        // Phase 10.1: run log_script over SSH (15s timeout §7.2 / §13.2)
        // Returns raw ordered lines (oldest->newest), each prefixed epoch-ms per §7.2 format.
        // No dedup, no trim, no scheduling yet.
        SshResult r = new SshConnection().runScriptWithTimeout(null, script, 15000);
        return r.stdout;
    }
}
