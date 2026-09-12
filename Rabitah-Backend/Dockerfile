FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY Rabitah-Backend/pom.xml pom.xml
COPY Rabitah-Backend/src src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/rabitah-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
