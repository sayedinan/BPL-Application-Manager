pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Auto-resolve a JDK 21 toolchain (Adoptium) when one isn't installed
// locally. Without this, the build fails on machines that don't have
// Java 21 pre-installed and don't have the toolchain auto-detection
// search paths configured. See:
//   https://docs.gradle.org/current/userguide/toolchains.html#sec:download_repositories
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "bpl-application-admin"
