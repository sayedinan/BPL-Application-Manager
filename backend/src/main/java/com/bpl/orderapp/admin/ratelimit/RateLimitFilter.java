package com.bpl.orderapp.admin.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Request-rate limits (SPEC §8.6): sign-in requests per address, and API and
 * WebSocket-handshake requests per address and per signed-in user. It runs inside
 * the security chain, after the session's user is known. Deliberately not a bean,
 * so Spring Boot does not also register it as an ordinary servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private static final String REJECTION_BODY = """
        {"type":"https://errors.bpl-orderapp.com/RATE_LIMITED","title":"Rate limited","status":429,\
        "detail":"Too many requests. Please wait and try again.","code":"RATE_LIMITED"}""";

    private final ClientAddress clientAddress;
    private final RateLimiter loginByAddress;
    private final RateLimiter apiByAddress;
    private final RateLimiter apiByUser;

    public RateLimitFilter(ClientAddress clientAddress, int loginPerSecond,
                           int apiPerAddressPerMinute, int apiPerUserPerMinute) {
        this.clientAddress = clientAddress;
        this.loginByAddress = new RateLimiter(loginPerSecond, Duration.ofSeconds(1));
        this.apiByAddress = new RateLimiter(apiPerAddressPerMinute, Duration.ofMinutes(1));
        this.apiByUser = new RateLimiter(apiPerUserPerMinute, Duration.ofMinutes(1));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return path(request).startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String address = clientAddress.of(request);
        long wait = apiByAddress.tryAcquire(address);
        if (wait == 0 && path(request).startsWith(LOGIN_PATH)) {
            wait = loginByAddress.tryAcquire(address);
        }
        if (wait == 0) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                wait = apiByUser.tryAcquire(auth.getName());
            }
        }
        if (wait > 0) {
            reject(response, wait);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Decoded, so "%6Cogin" cannot be used to dodge the sign-in limit. */
    private static String path(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    private static void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType("application/problem+json");
        response.getWriter().write(REJECTION_BODY);
    }
}
