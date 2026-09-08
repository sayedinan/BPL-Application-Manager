package com.bpl.orderapp.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BPL Order Application Admin — backend entry point.
 *
 * <p>See SPEC.md for the full specification.
 */
@SpringBootApplication
@EnableScheduling
public class BplApplicationAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(BplApplicationAdminApplication.class, args);
    }
}
