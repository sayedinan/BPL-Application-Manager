package com.bpl.orderapp.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BPL Order Application Admin — backend entry point.
 *
 * <p>See SPEC.md for the full specification.
 *
 * <h2>Spring Security is currently disabled</h2>
 * The login endpoint (POST /api/v1/auth/login) is implemented as a
 * pure credential check — no session cookie, no JWT, no security
 * filter chain. Spring Security's default behavior is to require
 * authentication on every endpoint, which would block the login
 * endpoint itself (chicken-and-egg). Rather than wire a partial
 * filter chain that will be torn down when sessions land, the
 * starter's auto-configuration is excluded here.
 *
 * <p>This is a temporary state. When session-based auth is wired
 * (per SPEC §8.1), the {@code exclude} attribute should be removed
 * and a real {@code SecurityFilterChain} bean added.
 */
@SpringBootApplication(exclude = {
    SecurityAutoConfiguration.class,
    ManagementWebSecurityAutoConfiguration.class
})
@EnableScheduling
public class BplApplicationAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(BplApplicationAdminApplication.class, args);
    }
}
