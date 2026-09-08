# ============================================================
# Dockerfile — Nino App
# Java 17 · Spring Boot 3.3
# Multi-stage: Maven build → JRE Alpine runtime
# ============================================================

# ── Stage 1: Build ───────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Cache layer: chỉ re-download khi pom.xml thay đổi
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Non-root user (bảo mật)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/app-nino.jar app.jar

RUN mkdir -p /app/uploads/avatars && \
    chown -R appuser:appgroup /app

USER appuser

EXPOSE 8086

# Health check — dùng context-path /api/v1 (SEC-25)
HEALTHCHECK --interval=24h --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8086/api/v1/internal/health-check || exit 1

ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
