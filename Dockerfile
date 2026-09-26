FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY customer-service/pom.xml customer-service/pom.xml
COPY fleet-service/pom.xml fleet-service/pom.xml
COPY booking-service/pom.xml booking-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline
COPY customer-service/src customer-service/src
COPY fleet-service/src fleet-service/src
COPY booking-service/src booking-service/src
COPY payment-service/src payment-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM eclipse-temurin:25-jre-noble
RUN groupadd --gid 10001 rental && useradd --uid 10001 --gid rental --no-create-home rental
WORKDIR /app
ARG SERVICE
COPY --from=build --chown=10001:10001 /workspace/${SERVICE}/target/app.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
