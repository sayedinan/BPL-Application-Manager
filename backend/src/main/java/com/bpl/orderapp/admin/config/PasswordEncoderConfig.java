package com.bpl.orderapp.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Password hashing for {@code users.password_hash} (SPEC §8.1).
 *
 * <p>Spring Boot does not auto-configure a {@code PasswordEncoder}
 * bean in the absence of a {@code UserDetailsService} — it only
 * creates an in-memory default user with a random password, which
 * is not what we want. The actual {@code UserDetailsService} against
 * the {@code users} table is a separate, later piece of work; this
 * bean exists now because the Sys.Admin seeder
 * ({@code com.bpl.orderapp.admin.auth.SysAdminSeeder}) needs a real
 * bcrypt encoder to hash the bootstrap password.
 *
 * <p>Strength 10 is the Spring Security default and a good
 * cost/security trade-off for an admin tool. If a future change
 * needs to raise it (e.g. compliance), do so here — the encoder is
 * a single bean and only the seeder consumes it today.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
