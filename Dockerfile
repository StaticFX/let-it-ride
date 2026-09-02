# syntax=docker/dockerfile:1

# ─────────────────────────────────────────────
# 1. Build the SPA
#    Pinned to the same bun we develop against — bump it here and in
#    .github/workflows/ci.yml together.
#
#    --platform=$BUILDPLATFORM: a JS bundle is the same bytes everywhere, so
#    this runs once on the native runner instead of once per target platform,
#    with the arm64 pass crawling through QEMU.
# ─────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM oven/bun:1.3.6 AS frontend

WORKDIR /app/frontend

# Dependencies first so a source-only change does not reinstall them.
COPY frontend/package.json frontend/bun.lock ./
RUN bun install --frozen-lockfile

COPY frontend/ ./
RUN bun run build

# ─────────────────────────────────────────────
# 2. Build the Kotlin server, with the SPA baked into its resources
#
#    Also native-only. The output is a jar — JVM bytecode does not care what
#    it was compiled on, and compiling Kotlin under emulation is by far the
#    most expensive thing this build could possibly do.
# ─────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS backend

WORKDIR /app

# Warm the Gradle + dependency caches on the build files alone.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/build.gradle.kts
RUN chmod +x gradlew && ./gradlew --no-daemon :backend:dependencies --configuration runtimeClasspath > /dev/null

COPY backend/src backend/src
COPY --from=frontend /app/frontend/dist frontend/dist

RUN ./gradlew --no-daemon :backend:buildFatJar

# ─────────────────────────────────────────────
# 3. Runtime — the only stage that is actually built per architecture, and it
#    is one COPY on top of a JRE.
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget && addgroup -S letitride && adduser -S letitride -G letitride

WORKDIR /app
COPY --from=backend /app/backend/build/libs/let-it-ride.jar app.jar

USER letitride

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=25s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/api/health" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
