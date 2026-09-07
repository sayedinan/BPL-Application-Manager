# syntax=docker/dockerfile:1.7
#
# Multi-stage build — distroless Java 21 runtime (SPEC §11.1).
# Read-only filesystem, non-root user, <150MB target image.

# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Cache Gradle wrapper + dependencies first for faster subsequent builds.
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY gradlew ./gradlew
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Now copy sources and build the bootJar.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# --- runtime ---
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app

# Spring Boot's layered jar extracts more efficiently than a fat jar.
COPY --from=build /src/build/libs/*.jar /app/app.jar

# Distroless nonroot uid = 65532.
USER 65532:65532

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
