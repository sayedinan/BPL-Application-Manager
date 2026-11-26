package com.bpl.orderapp.admin.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The address a limit is counted against. {@code X-Forwarded-For} is read only
 * when {@code rate-limit.trust-forwarded-for} is on, which is safe only if the
 * backend is reachable solely through the reverse proxy: otherwise any client
 * could send a new fake address with each request. The proxy appends the address
 * it saw, so only the last entry is trustworthy.
 */
@Component
public class ClientAddress {

    private final boolean trustForwardedFor;

    public ClientAddress(@Value("${rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String of(HttpServletRequest request) {
        if (trustForwardedFor) {
            String header = request.getHeader("X-Forwarded-For");
            if (header != null && !header.isBlank()) {
                String last = header.substring(header.lastIndexOf(',') + 1).trim();
                if (!last.isEmpty()) return last;
            }
        }
        return request.getRemoteAddr();
    }
}
