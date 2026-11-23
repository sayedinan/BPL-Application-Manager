# syntax=docker/dockerfile:1.7
#
# Multi-stage build — distroless Java 25 runtime (SPEC §11.1).
# Read-only filesystem, non-root user, <150MB target image.
# Fallback if gcr.io/distroless/java25-debian13:nonroot is unavailable:
#   FROM eclipse-temurin:25-jre
#   RUN useradd -u 65532 -r app
#   USER 65532:65532

# --- build ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Cache Gradle wrapper + dependencies first for faster subsequent builds.
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY gradlew ./gradlew
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Now copy sources and build the bootJar.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# Writable directories for the SQLite database and its backups. Created here
# (distroless has no shell to mkdir/chown) and copied below owned by uid 65532,
# so a fresh named volume mounted on /data or /backups inherits that ownership.
# The placeholder file makes sure the directory is copied even though it is empty.
RUN mkdir -p /out/data /out/backups && touch /out/data/.keep /out/backups/.keep

# --- runtime ---
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app

# Spring Boot's layered jar extracts more efficiently than a fat jar.
COPY --from=build /src/build/libs/*.jar /app/app.jar

COPY --from=build --chown=65532:65532 /out/data /data
COPY --from=build --chown=65532:65532 /out/backups /backups

# Distroless nonroot uid = 65532.
USER 65532:65532

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
