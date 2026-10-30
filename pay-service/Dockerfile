FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd -r -u 10001 pay && mkdir -p /app/data && chown pay /app/data
COPY --from=build /src/target/pay-service-*.jar /app/pay-service.jar
USER pay
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:MaxMetaspaceSize=96m -Xss512k -XX:+UseSerialGC"
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/pay-service.jar"]
