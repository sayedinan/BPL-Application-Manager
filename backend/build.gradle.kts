plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.bpl.orderapp"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        // SPEC §10 pins Java 21. System currently has only 17 and 25
        // available, so the build will download a 21 toolchain on the
        // first run (requires internet access). To skip the download
        // and use an existing JDK, override on the command line:
        //   ./gradlew -Dorg.gradle.java.installations.auto-download=false
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web + validation
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Security + session-based auth (SPEC §8.1)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.session:spring-session-jdbc")

    // Persistence (SPEC §3 — JPA + Flyway)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // WebSocket / STOMP (SPEC §5)
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Actuator (SPEC §11.3)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Field-level encryption for SSH credentials (SPEC §8.3).
    // spring-security-crypto ships with the security starter transitively,
    // but it's pulled in explicitly here so the cipher choice isn't
    // accidental if the security starter's deps change.
    implementation("org.springframework.security:spring-security-crypto")

    // SSH for application start/stop/log execution (SPEC §3, §6, §7)
    implementation("com.jcraft:jsch:0.1.55")

    // Rate limiting (SPEC §8.6)
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Time types in JSON (java.time.* — SPEC §3.3 all TIMESTAMPTZ)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // SSH_CIPHER_KEY is required by SshCredentialCipher at construction
    // time. There is intentionally NO default in this build script —
    // committing a key value, even a test-only one, would defeat the
    // "encryption key not hardcoded or committed" requirement (SPEC
    // §3.2 / §8.3). The check runs at task-execution time (doFirst)
    // so unrelated tasks like bootRun aren't blocked by it.
    doFirst {
        val key = System.getenv("SSH_CIPHER_KEY")
        if (key == null || key.isEmpty()) {
            throw GradleException(
                "SSH_CIPHER_KEY is not set. Set a 32+ char test-only key in " +
                "the environment before running tests, e.g.\n" +
                "  SSH_CIPHER_KEY='test-only-32-char-key-set-by-CI-not-committed' ./gradlew test"
            )
        }
    }
}
