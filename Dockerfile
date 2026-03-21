# ── Stage 1: Build the JAR with Maven ──────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml first (Docker cache optimization — deps only re-download on pom change)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: Lean runtime image ────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy only the built JAR
COPY --from=builder /app/target/tamil-voice-assistant-1.0.0.jar app.jar

# Expose port (Railway/Render override this via $PORT env var)
EXPOSE 8080

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
