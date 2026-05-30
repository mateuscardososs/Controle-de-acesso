FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl && addgroup -S app && adduser -S app -G app

WORKDIR /app

ENV JAVA_OPTS="" \
    LOG_FILE=/app/logs/access-control-api.log \
    FACE_UPLOAD_DIR=/app/uploads/faces

COPY --from=build /workspace/target/*.jar /app/app.jar

RUN mkdir -p /app/uploads/faces /app/logs && chown -R app:app /app

USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
