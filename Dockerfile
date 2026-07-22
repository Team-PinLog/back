FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.source="https://github.com/Team-PinLog/back"

RUN addgroup -g 1000 app && adduser -u 1000 -G app -D app

ARG BUILD_SHA=unknown
ENV BUILD_SHA=${BUILD_SHA}

COPY --from=build /workspace/build/libs/*.jar /app.jar

USER 1000
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app.jar"]
