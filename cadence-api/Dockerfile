# syntax=docker/dockerfile:1.7
# Cadence API. Build from the repo root: docker build -f cadence-api/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Maven Toolchains (DECISIONS.md D19): register this image's JDK 21 globally
RUN printf '<toolchains><toolchain><type>jdk</type><provides><version>21</version></provides><configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>' "$JAVA_HOME" > /usr/share/maven/conf/toolchains.xml
COPY pom.xml ./
COPY cadence-events/pom.xml cadence-events/
COPY cadence-api/pom.xml cadence-api/
COPY cadence-transcoder/pom.xml cadence-transcoder/
COPY cadence-e2e/pom.xml cadence-e2e/
COPY cadence-web/pom.xml cadence-web/
COPY cadence-loadtest/pom.xml cadence-loadtest/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl cadence-api -am dependency:go-offline || true
COPY cadence-events/src cadence-events/src
COPY cadence-api/src cadence-api/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl cadence-api -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app cadence
WORKDIR /app
COPY --from=build /src/cadence-api/target/cadence-api-*.jar /app/app.jar
USER cadence
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
