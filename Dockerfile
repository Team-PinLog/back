# 이미지는 러너가 미리 만든 jar를 받는다. 컨테이너 안에서 Gradle을 다시 돌리면 CI가 같은 코드를
# 두 번 컴파일하고(실측 33.4초), 그 레이어는 src가 매 PR 바뀌므로 레이어 캐시로도 지울 수 없다.
#
# 대가로 이 파일은 소스만으로 혼자 빌드되지 않는다. `./gradlew bootJar`가 선행이다 — README의
# 「컨테이너 이미지」를 따른다. 근거와 감수하는 것은 docs/backend/decisions/BD-44에 있다.
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.source="https://github.com/Team-PinLog/back"

RUN addgroup -g 1000 app && adduser -u 1000 -G app -D app

ARG BUILD_SHA=unknown
ENV BUILD_SHA=${BUILD_SHA}

COPY build/libs/*.jar /app.jar

USER 1000
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app.jar"]
