# ============================================================================
# Multi-stage Dockerfile for securebank-gateway (spec §7).
# Stage 1 builds the fat jar (stubs are generated from vendored protos during the
# Maven build — protoc is fetched by protobuf-maven-plugin). Stage 2 is a slim
# JRE runtime image exposing the single public port 8080.
# ============================================================================

# ---- Stage 1: build --------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first: copy only the POM, resolve, then copy sources.
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline || true

# Copy sources (includes src/main/proto for stub generation).
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- Stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root user for defense in depth.
RUN useradd --system --uid 10001 gateway
USER gateway

COPY --from=build /build/target/securebank-gateway-*.jar app.jar

# The gateway is the only publicly reachable backend.
EXPOSE 8080

# Default to the docker profile so service-name routing applies inside containers.
ENV SPRING_PROFILES_ACTIVE=docker

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
