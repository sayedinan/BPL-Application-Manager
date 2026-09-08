package com.bpl.orderapp.admin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Spring Security filter chain (SPEC §8.1, §8.2).
 *
 * <p>This is the minimum chain needed for session-based auth to
 * actually run. The login endpoint writes a SecurityContext into
 * the holder, Spring Session's filter persists the SPRING_SESSION
 * row in the database, and the response gets a {@code SESSION}
 * cookie. The filter chain's job here is narrow:
 *
 * <ol>
 *   <li>Permit the login endpoint and the actuator health
 *       endpoint without authentication. Login is what produces a
 *       session, so it can't require a session; health is
 *       unauthenticated by industry convention.</li>
 *   <li>For every other request, require authentication. The
 *       controller-driven approach to authentication (the
 *       {@code AuthController} writes the SecurityContext on
 *       successful login) means the filter chain doesn't need to
 *       know about credentials — it just enforces "do you have
 *       a session?"</li>
 *   <li>Use {@code IF_REQUIRED} session creation: Spring Session
 *       writes a row only when something is actually put in the
 *       SecurityContextHolder. Unauthenticated requests don't
 *       create a session row.</li>
 * </ol>
 *
 * <h2>What this configuration does NOT do</h2>
 * <ul>
 *   <li>It does not enforce the mustChangePassword lock. That
 *       rule is fundamentally a frontend concern (route guard);
 *       server-side enforcement is forward-looking and lands
 *       with a more complete filter chain.</li>
 *   <li>It does not enforce Admin+ on the user-management
 *       endpoints. The reset-password controller documents this
 *       gap; a future filter chain will add role-based access
 *       for /api/v1/users/**.</li>
 *   <li>It does not implement logout. The SPEC's /auth/logout
 *       endpoint (invalidate the session) lands in a separate
 *       slice.</li>
 * </ul>
 *
 * <h2>Why permitAll on /actuator/health and not /actuator/info</h2>
 * {@code info} requires explicit content to be useful, and
 * exposing it without authentication is a small information leak
 * (build version, git commit, etc.). Health is required for
 * load-balancer probes; info is not. Both are public in the
 * current SPEC §13.1 config (which only exposes health+info),
 * but only health is on the public path here.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF is disabled for the API endpoints. The session
            // cookie is HttpOnly + SameSite=Strict, which means
            // cross-origin browser requests cannot read the
            // cookie value, so a CSRF attack against a same-origin
            // browser-context request would need the victim's
            // browser to actively fetch a cross-origin URL. For
            // a pure JSON API consumed by the SPA, the cookie's
            // SameSite=Strict attribute is the primary defense;
            // CSRF tokens add ceremony without a corresponding
            // security gain. If a future browser-context
            // endpoint changes this calculus, re-enable CSRF and
            // issue tokens to the SPA on session creation.
            .csrf(csrf -> csrf.disable())

            // Permitted paths. Login is the entry point; /auth/me
            // and /auth/logout are also permitted at the filter-
            // chain level so that an unauthenticated request
            // reaches the controller, which then returns the
            // right response (401 for /me, idempotent 204 for
            // /logout). If we let Spring Security's default 403
            // path handle the unauthenticated case, the SPA would
            // see a different error shape than the rest of the
            // auth flow. Health is the load-balancer probe.
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/me",
                    "/api/v1/auth/logout",
                    "/actuator/health"
                ).permitAll()
                .anyRequest().authenticated()
            )

            // Session policy: only create a session when
            // something is actually put in the SecurityContext
            // (i.e. after a successful login). Unauthenticated
            // requests don't get a session row.
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            );

        return http.build();
    }

    /**
     * The {@code SecurityContextRepository} bean that the
     * AuthController uses to persist the SecurityContext after
     * a successful login. Spring Security 6 does not auto-configure
     * this bean when the rest of the security autoconfig is in its
     * minimal "permit login, deny everything else" state, so we
     * declare it explicitly.
     *
     * <p>This is the standard {@code HttpSessionSecurityContextRepository},
     * which stores the SecurityContext in the HTTP session. With
     * Spring Session JDBC on the classpath, the underlying
     * {@code HttpSession} is a {@code SessionRepositoryFilter.SessionRepositoryRequestWrapper}
     * that persists the session attributes (including this
     * SecurityContext) to the {@code SPRING_SESSION} table on
     * commit. The {@code SESSION} cookie is also set by Spring
     * Session on the same write path.
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
