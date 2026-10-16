# -----------------------------------------------------------------------------
# Runtime-only backend image — expects bootJar to have been built LOCALLY first.
#
# Recommended (fast):
#   ./scripts/dev.sh package          # builds backend/build/libs/*.jar
#   ./scripts/dev.sh build-images     # or 'up' — both will auto-run the above
#
# Pure-Docker fallback (slow, no cache reuse — use only if no JDK on host):
#   See comments at the end of this file for a 'builder' stage snippet.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install curl for HEALTHCHECK and any runtime debugging.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy the pre-built Spring Boot fat-jar.
# (built by: cd backend && ./gradlew bootJar -x test --no-daemon)
COPY build/libs/*.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS=""
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# -----------------------------------------------------------------------------
# === Pure-Docker multi-stage builder (OPTIONAL / SLOW) ===
# Use this ONLY when you cannot build the jar on the host (no JDK).
# It re-downloads Gradle + all Maven dependencies inside the container every
# time the Docker cache is invalidated — very slow on restricted networks.
#
#   # syntax=docker/dockerfile:1.6
#   FROM eclipse-temurin:17-jdk-jammy AS builder
#   WORKDIR /app
#   COPY gradlew gradlew.bat ./
#   COPY gradle ./gradle
#   COPY build.gradle settings.gradle ./
#   COPY src ./src
#   # BuildKit cache mount — persist the wrapper zip + Maven deps across builds.
#   # Remove this line if your Docker engine doesn't support BuildKit.
#   RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
#       chmod +x ./gradlew && ./gradlew bootJar -x test --no-daemon
#
#   FROM eclipse-temurin:17-jre-jammy
#   WORKDIR /app
#   RUN apt-get update && apt-get install -y --no-install-recommends curl \
#       && rm -rf /var/lib/apt/lists/*
#   COPY --from=builder /app/build/libs/*.jar app.jar
#   EXPOSE 8080
#   HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
#       CMD curl -sf http://localhost:8080/actuator/health || exit 1
#   ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
# -----------------------------------------------------------------------------
