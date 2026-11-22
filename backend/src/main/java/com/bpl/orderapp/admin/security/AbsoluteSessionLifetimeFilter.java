package com.bpl.orderapp.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Hard cap on how long a login stays valid, however often it is used.
 *
 * <p>"Remember me" sessions slide: every request pushes the 30-day idle
 * timeout forward, so a session that is used regularly (or a stolen cookie
 * that is replayed regularly) would otherwise never expire. This filter
 * invalidates any session older than {@link #MAX_SESSION_AGE}, measured from
 * the moment it was created at login, and the user has to sign in again.
 *
 * <p>It runs before the security context is loaded, so after invalidation the
 * request is simply unauthenticated and gets the normal 401.
 */
public class AbsoluteSessionLifetimeFilter extends OncePerRequestFilter {

    public static final Duration MAX_SESSION_AGE = Duration.ofDays(90);

    private static final Logger log = LoggerFactory.getLogger(AbsoluteSessionLifetimeFilter.class);

    private final Clock clock;

    public AbsoluteSessionLifetimeFilter() {
        this(Clock.systemUTC());
    }

    AbsoluteSessionLifetimeFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            long ageMillis = clock.millis() - session.getCreationTime();
            if (ageMillis > MAX_SESSION_AGE.toMillis()) {
                log.info("Session {} exceeded the {}-day absolute lifetime; invalidating",
                    session.getId(), MAX_SESSION_AGE.toDays());
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
