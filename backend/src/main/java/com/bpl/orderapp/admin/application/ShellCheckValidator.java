package com.bpl.orderapp.admin.application;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class ShellCheckValidator {

    /** Thrown when ShellCheck ran and reported error-severity findings. Mapped to 400 SHELLCHECK_FAILED. */
    public static class ShellCheckFailedException extends RuntimeException {
        private final transient List<Map<String, String>> findings;
        public ShellCheckFailedException(List<Map<String, String>> findings) {
            super("SHELLCHECK_FAILED: " + findings);
            this.findings = findings;
        }
        public List<Map<String, String>> getFindings() { return findings; }
    }

    /** Thrown only when ShellCheck itself could not run (not installed, timed out). Callers fail open and audit. */
    public static class ShellCheckUnavailableException extends Exception {
        public ShellCheckUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }

    public List<Map<String,String>> validate(String script) throws ShellCheckUnavailableException {
        if (script == null || script.isBlank()) return List.of();
        List<Map<String,String>> errors = new ArrayList<>();
        Process p = null;
        try {
            p = new ProcessBuilder("shellcheck", "--format=json", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ShellCheckUnavailableException("shellcheck timed out", null);
            }
            com.fasterxml.jackson.databind.JsonNode arr = new ObjectMapper().readTree(out);
            for (com.fasterxml.jackson.databind.JsonNode o : arr) {
                if ("error".equals(o.path("severity").asText())) {
                    Map<String,String> d = new HashMap<>();
                    d.put("line", String.valueOf(o.path("line").asInt(0)));
                    d.put("message", o.path("message").asText(""));
                    errors.add(d);
                }
            }
        } catch (java.io.IOException e) {
            // Fail-open (§12.3 v1.1) ONLY when ShellCheck is unavailable.
            throw new ShellCheckUnavailableException("shellcheck unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShellCheckUnavailableException("shellcheck interrupted", e);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
        if (!errors.isEmpty()) throw new ShellCheckFailedException(errors);
        return errors;
    }
}
