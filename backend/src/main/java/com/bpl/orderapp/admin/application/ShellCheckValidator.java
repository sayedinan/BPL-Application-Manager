package com.bpl.orderapp.admin.application;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
@Component
public class ShellCheckValidator {
    public List<Map<String,String>> validate(String script) throws Exception {
        List<Map<String,String>> errors = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("shellcheck", "--format=json", "-").start();
            p.getOutputStream().write(script.getBytes()); p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes());
            // Parse JSON array output from --format=json
            com.fasterxml.jackson.databind.JsonNode arr = new ObjectMapper().readTree(out);
            for (com.fasterxml.jackson.databind.JsonNode o : arr) {
                if ("error".equals(o.path("severity").asText())) {
                    Map<String,String> d = new HashMap<>();
                    d.put("line", String.valueOf(o.path("line").asInt(0)));
                    d.put("message", o.path("message").asText(""));
                    errors.add(d);
                }
            }
            if (!errors.isEmpty()) throw new RuntimeException("SHELLCHECK_FAILED: " + errors);
        } catch (java.io.IOException e) {
            // Fail-open (§12.3 v1.1): save proceeds; audit wired 9.3
            // Audit: ACTION=SHELLCHECK_UNAVAILABLE, RESULT=SUCCESS,
            // detail notes unvalidated script(s) — wired by caller (Phase 9).
        }
        return errors;
    }
}
