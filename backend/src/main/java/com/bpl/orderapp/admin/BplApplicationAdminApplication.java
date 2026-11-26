package com.bpl.orderapp.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BPL Order Application Admin — backend entry point.
 *
 * <p>See SPEC.md for the full specification.
 */
// Sign-in is handled by AuthController, so Spring's default in-memory user (and the
// password it would log at every start) is not wanted.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableAsync
public class BplApplicationAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(BplApplicationAdminApplication.class, args);
    }
}
