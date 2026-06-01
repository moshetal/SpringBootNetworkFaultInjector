FROM maven:3.9-eclipse-temurin-17 AS library
WORKDIR /build
COPY pom.xml mvnw ./
COPY fault-injector-core fault-injector-core
COPY fault-injector-protocol fault-injector-protocol
COPY fault-injector-actuator fault-injector-actuator
COPY fault-injector-ui fault-injector-ui
COPY fault-injector-agent fault-injector-agent
COPY spring-boot-starter-fault-injector spring-boot-starter-fault-injector
COPY spring-boot-starter-fault-injector-agent spring-boot-starter-fault-injector-agent
RUN mvn -B -q install -DskipTests

FROM maven:3.9-eclipse-temurin-17 AS server
WORKDIR /build
COPY --from=library /root/.m2 /root/.m2
COPY platform/pom.xml platform/pom.xml
COPY platform/fault-injector-server platform/fault-injector-server
WORKDIR /build/platform
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=server /build/platform/fault-injector-server/target/fault-injector-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
