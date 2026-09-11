FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY apps/core-api/pom.xml apps/core-api/pom.xml
RUN mvn -f apps/core-api/pom.xml dependency:go-offline -B
COPY database/migrations database/migrations
COPY apps/core-api/src apps/core-api/src
RUN mvn -f apps/core-api/pom.xml package -DskipTests -B
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && groupadd --system studyos && useradd --system --gid studyos studyos
COPY --from=build /workspace/apps/core-api/target/core-api-0.1.0.jar app.jar
USER studyos
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
