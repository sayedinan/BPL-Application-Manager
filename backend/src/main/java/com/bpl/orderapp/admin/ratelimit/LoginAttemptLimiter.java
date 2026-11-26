package com.bpl.orderapp.admin.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Slows password guessing (SPEC §8.6). Counted per username AND client address,
 * so someone guessing from elsewhere cannot lock the real user out. It does not
 * stop guessing spread across many addresses.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    private final RateLimiter attempts;
    private final ClientAddress clientAddress;

    public LoginAttemptLimiter(
            @Value("${rate-limit.login-failures:10}") int failures,
            @Value("${rate-limit.login-failure-window:15m}") Duration window,
            ClientAddress clientAddress) {
        this.attempts = new RateLimiter(failures, window);
        this.clientAddress = clientAddress;
    }

    /**
     * Counts an attempt BEFORE the password is checked, so parallel requests
     * cannot all slip through. Call {@link #succeeded} to clear the count.
     */
    public void acquire(String username, HttpServletRequest request) {
        long wait = attempts.tryAcquire(key(username, request));
        if (wait > 0) {
            log.warn("Password attempts blocked for user '{}' from {}", username, clientAddress.of(request));
            throw new TooManyRequestsException(wait);
        }
    }

    public void succeeded(String username, HttpServletRequest request) {
        attempts.reset(key(username, request));
    }

    private String key(String username, HttpServletRequest request) {
        return clientAddress.of(request) + '|' + username.toLowerCase(Locale.ROOT);
    }
}
