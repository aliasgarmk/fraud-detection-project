# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Download dependencies separately so this layer is cached between code changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the fat JAR (skip tests — tests run in CI, not in the image build)
COPY src/ src/
RUN mvn clean package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /build/target/fraud-detection-client-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
