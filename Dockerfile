FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
WORKDIR /app

COPY --from=build /app/target/*.jar /app/axion-auth.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/axion-auth.jar"]
