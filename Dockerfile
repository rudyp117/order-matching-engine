FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /project
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S exchange && adduser -S exchange -G exchange
WORKDIR /app
COPY --from=build /project/target/order-matching-engine-1.0.0.jar app.jar
USER exchange
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
