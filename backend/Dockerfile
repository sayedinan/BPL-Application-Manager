# syntax=docker/dockerfile:1.7
#
# Multi-stage build — distroless Java 25 runtime (SPEC §11.1).
# Read-only filesystem, non-root user, <150MB target image.
# Fallback if gcr.io/distroless/java25-debian13:nonroot is unavailable:
#   FROM eclipse-temurin:25-jre
#   RUN useradd -u 65532 -r app
#   USER 65532:65532

# --- build ---
FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /src

# Cache Gradle wrapper + dependencies first for faster subsequent builds.
COPY settings.gradle.kts build.gradle.kts gradle.lockfile ./
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
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:4618e7a1a1f5177222985347e1ffd503076f6be5837b89c8fd02800bd8551bde
WORKDIR /app

# Spring Boot's layered jar extracts more efficiently than a fat jar.
COPY --from=build /src/build/libs/*.jar /app/app.jar

# Script validation when applications are saved. The image holds only this static binary.
COPY --from=koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d /bin/shellcheck /usr/local/bin/shellcheck

COPY --from=build --chown=65532:65532 /out/data /data
COPY --from=build --chown=65532:65532 /out/backups /backups

# Distroless nonroot uid = 65532.
USER 65532:65532

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
