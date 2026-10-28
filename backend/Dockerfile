# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 booking \
    && useradd --uid 10001 --gid booking --no-create-home booking \
    && mkdir -p /app/local && chown -R booking:booking /app
WORKDIR /app
COPY --from=build --chown=booking:booking /build/target/backend-0.0.1-SNAPSHOT.jar /app/backend.jar
USER booking
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
