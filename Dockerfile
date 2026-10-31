# syntax=docker/dockerfile:1
# ---------- build stage: compile the Spring Boot jar ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- runtime stage: small JRE image ----------
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --no-create-home bmb
WORKDIR /app
COPY --from=build /src/target/bhai-mobile-billing.jar app.jar
USER bmb
ENV TZ=Asia/Kolkata \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE 8080
# no curl in the JRE image -> use bash /dev/tcp to call /api/ping
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /api/ping HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3'
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
