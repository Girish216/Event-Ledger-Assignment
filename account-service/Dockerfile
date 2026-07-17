FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/pom.xml event-gateway/pom.xml
RUN chmod +x mvnw

COPY account-service/src account-service/src
RUN ./mvnw -q -pl account-service -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/account-service/target/account-service-*-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
