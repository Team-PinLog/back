# Swagger/OpenAPI(springdoc) Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** springdoc-openapi를 붙여 `/api/core/swagger-ui.html`와 `/api/core/v3/api-docs`를 노출하고, prod에서는 끈다.

**Architecture:** springdoc-openapi-starter-webmvc-ui 의존성을 추가하면 런타임에 OpenAPI 문서와 Swagger UI가 자동 생성된다. `global/config/OpenApiConfig`가 문서 메타데이터(title/version/description)를 정의하고, 노출은 프로파일로 제어한다(기본 on, `application-prod.yml`에서 off). 컨트롤러가 0개라 문서 `paths`는 비어 있으나 이는 정상이며 컨트롤러 추가 시 자동 반영된다.

**Tech Stack:** Spring Boot 4.1.0, Java 21, springdoc-openapi 3.0.x, JUnit 5, Testcontainers(PostgreSQL), Gradle.

## Global Constraints

- Spring Boot `4.1.0`, Java toolchain `21`.
- 서비스 context-path는 `/api/core`. 컨트롤러/설정 경로에 중복 표기 금지.
- Checkstyle = Naver 컨벤션, `maxWarnings=0`이 CI 게이트. 들여쓰기는 **탭**(기존 소스와 동일).
- Hibernate `ddl-auto=validate`, 스키마는 Flyway 소유. 테스트에서 DB가 필요하면 **PostgreSQL Testcontainers**(H2 금지).
- 비밀값을 저장소에 넣지 않음.
- 설정 클래스는 `com.pinlog.pinlogback.global.config` 패키지에 둔다(패키지 첫 클래스면 이때 생성).
- CI 게이트 명령: `./gradlew clean check --no-daemon`.
- Jira: `S15P11A705-24`. 브랜치 `feat/S15P11A705-24-swagger-openapi`, 커밋 접두 `feat(S15P11A705-24):` / `docs(S15P11A705-24):` / `test(S15P11A705-24):`.

## File Structure

- `build.gradle` — springdoc 의존성 추가(수정).
- `src/main/java/com/pinlog/pinlogback/global/config/OpenApiConfig.java` — OpenAPI 메타 빈(신규).
- `src/main/resources/application-prod.yml` — prod에서 springdoc off(신규).
- `src/test/java/com/pinlog/pinlogback/OpenApiDocsTests.java` — 문서 생성 스모크 테스트(신규).
- (로컬 전용, 커밋 안 함) `SampleController.java` + `PingResponse.java` — Swagger UI 육안 확인용.

---

### Task 1: springdoc 의존성 + 문서 생성 스모크 테스트

springdoc를 추가하면 `/api/core/v3/api-docs`가 매핑된다. 의존성이 없으면 이 경로는 404다. 이 차이로 TDD한다.
이 테스트는 전체 컨텍스트를 띄우므로(JPA→DB 필요) `DeploymentContractTests`와 동일하게 Testcontainers Postgres를 쓰고 Redis 헬스는 끈다.

**Files:**
- Modify: `build.gradle` (dependencies 블록)
- Test: `src/test/java/com/pinlog/pinlogback/OpenApiDocsTests.java` (신규)
- Reference: `src/test/java/com/pinlog/pinlogback/DeploymentContractTests.java`, `src/test/java/com/pinlog/pinlogback/integration/PostgresContainerSupport.java`

**Interfaces:**
- Consumes: `PostgresContainerSupport`(기존 베이스, `POSTGRES` 컨테이너 제공).
- Produces: `OpenApiDocsTests` — `/api/core/v3/api-docs` 200 & 유효 OpenAPI 문서 검증. Task 2가 이 클래스에 title 검증을 추가한다.

- [ ] **Step 1: 실패하는 스모크 테스트 작성**

`src/test/java/com/pinlog/pinlogback/OpenApiDocsTests.java`:

```java
package com.pinlog.pinlogback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@Testcontainers
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "management.health.redis.enabled=false"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiDocsTests extends PostgresContainerSupport {

	@Container
	static final PostgreSQLContainer<?> postgres = POSTGRES;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	void openApiDocsAreGeneratedUnderServiceContextPath() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/core/v3/api-docs"))
			.GET()
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"openapi\""));
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.pinlog.pinlogback.OpenApiDocsTests" --no-daemon`
Expected: FAIL — `/api/core/v3/api-docs`가 매핑 안 돼 404 반환, `assertEquals(200, ...)`에서 실패.

- [ ] **Step 3: springdoc 의존성 추가**

`build.gradle`의 `dependencies` 블록에 아래 한 줄을 추가한다(다른 `implementation` 항목들 사이, 정렬 관례 유지):

```groovy
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3'
```

> 3.0.x가 Spring Boot 4.0 기준 릴리스다. Boot 4.1과의 minor 호환은 이 태스크의 테스트로 검증된다.
> 빌드가 의존성 해석에 실패하거나 기동이 깨지면 Maven Central에서 더 최신 3.0.x(또는 Boot 4.1 명시 지원 버전)로 올려 재시도한다.

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.pinlog.pinlogback.OpenApiDocsTests" --no-daemon`
Expected: PASS — 200 & body에 `"openapi"` 포함(컨트롤러 0개라 `paths`는 비어도 됨).

- [ ] **Step 5: 커밋**

```bash
git add build.gradle src/test/java/com/pinlog/pinlogback/OpenApiDocsTests.java
git commit -m "feat(S15P11A705-24): add springdoc-openapi and doc-generation smoke test

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: OpenAPI 메타데이터 빈

문서에 서비스 정보(title/version/description)를 부여한다. Task 1의 스모크 테스트에 title 검증을 덧붙여 TDD한다.

**Files:**
- Create: `src/main/java/com/pinlog/pinlogback/global/config/OpenApiConfig.java`
- Test: `src/test/java/com/pinlog/pinlogback/OpenApiDocsTests.java` (Task 1에서 생성, 메서드 추가)

**Interfaces:**
- Consumes: springdoc(Task 1), `io.swagger.v3.oas.models.OpenAPI`/`Info`(springdoc-ui가 가져오는 swagger-models).
- Produces: `OpenApiConfig#pinlogOpenApi()` `@Bean OpenAPI` — `info.title = "PinLog Core API"`.

- [ ] **Step 1: 실패하는 title 검증 테스트 추가**

`OpenApiDocsTests`에 메서드를 추가한다(기존 import·필드 재사용):

```java
	@Test
	void openApiInfoHasServiceTitle() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/core/v3/api-docs"))
			.GET()
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"title\":\"PinLog Core API\""));
	}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.pinlog.pinlogback.OpenApiDocsTests" --no-daemon`
Expected: FAIL — 기본 title(예: "OpenAPI definition")이라 `"title":"PinLog Core API"` 미포함.

- [ ] **Step 3: OpenApiConfig 작성**

`src/main/java/com/pinlog/pinlogback/global/config/OpenApiConfig.java` (들여쓰기 탭):

```java
package com.pinlog.pinlogback.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI pinlogOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("PinLog Core API")
				.version("0.0.1-SNAPSHOT")
				.description("PinLog 백엔드 코어 API 문서"));
	}
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.pinlog.pinlogback.OpenApiDocsTests" --no-daemon`
Expected: PASS — 두 메서드 모두 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/pinlog/pinlogback/global/config/OpenApiConfig.java src/test/java/com/pinlog/pinlogback/OpenApiDocsTests.java
git commit -m "feat(S15P11A705-24): add OpenAPI metadata bean

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: prod 프로파일에서 노출 off + 전체 게이트 통과

local/dev(무프로파일)는 켜두고 prod만 끈다. spec 결정에 따라 prod 프로파일 통합 테스트는 컨텍스트 로딩 리스크로 만들지 않고, 설정 파일 + 수동 확인으로 검증한다. 마지막에 CI 게이트 전체를 돌린다.

**Files:**
- Create: `src/main/resources/application-prod.yml`

**Interfaces:**
- Consumes: springdoc 설정 키 `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled`.
- Produces: (없음 — 배포 시 `SPRING_PROFILES_ACTIVE=prod`에서 문서 경로 비노출)

- [ ] **Step 1: application-prod.yml 작성**

`src/main/resources/application-prod.yml` (신규):

```yaml
# 운영에서는 API 문서를 노출하지 않는다. 로컬·개발(무프로파일)에서는 기본값(on)이 적용된다.
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

- [ ] **Step 2: prod 비노출 수동 확인**

Run: `SPRING_PROFILES_ACTIVE=prod` 로 기동은 DB 자격증명 주입이 필요해 로컬에서 부담이 크므로, 설정만 확인한다.
Run: `./gradlew test --tests "com.pinlog.pinlogback.OpenApiDocsTests" --no-daemon`
Expected: PASS — 무프로파일 테스트는 여전히 통과(prod 설정은 무프로파일에 영향 없음).

- [ ] **Step 3: 전체 CI 게이트 통과 확인**

Run: `./gradlew clean check --no-daemon`
Expected: BUILD SUCCESSFUL — 기존 `DeploymentContractTests`(`/api/core/not-found`→404 등) 포함 전 테스트 통과, Checkstyle `maxWarnings=0` 통과.

- [ ] **Step 4: 커밋**

```bash
git add src/main/resources/application-prod.yml
git commit -m "feat(S15P11A705-24): disable swagger in prod profile

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: (로컬 전용, 커밋 안 함) Swagger UI 육안 확인

컨트롤러가 0개라 UI는 비어 있다. Swagger UI에 엔드포인트가 실제로 렌더링되는지 임시 컨트롤러로 눈으로 확인한 뒤 **되돌린다(커밋하지 않는다)**. package-structure는 샘플 패키지를 금하므로 이 코드는 영구 산출물이 아니다.

**Files (임시, 커밋 금지):**
- Create(임시): `src/main/java/com/pinlog/pinlogback/sample/SampleController.java`
- Create(임시): `src/main/java/com/pinlog/pinlogback/sample/PingResponse.java`

- [ ] **Step 1: 임시 컨트롤러·DTO 작성**

`.../sample/PingResponse.java`:

```java
package com.pinlog.pinlogback.sample;

import java.time.Instant;

public record PingResponse(String message, Instant serverTime) {
}
```

`.../sample/SampleController.java`:

```java
package com.pinlog.pinlogback.sample;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "sample", description = "임시 확인용 (커밋하지 않음)")
@RestController
@RequestMapping("/samples")
public class SampleController {

	@Operation(summary = "ping", description = "Swagger 렌더링 확인용 임시 엔드포인트")
	@GetMapping("/ping")
	public PingResponse ping() {
		return new PingResponse("pong", Instant.now());
	}
}
```

- [ ] **Step 2: 앱 기동**

Run: `./gradlew bootRun` (Postgres/Redis는 spring-boot-docker-compose가 기동; 이미 떠 있으면 재사용)
Expected: `Started PinlogBackApplication`, Tomcat context-path `/api/core`.

- [ ] **Step 3: Swagger UI 육안 확인**

브라우저로 `http://localhost:8080/api/core/swagger-ui.html` 열기.
Expected: `sample` 태그 아래 `GET /samples/ping`이 보이고, "Try it out" 실행 시 `{"message":"pong","serverTime":"...Z"}` 200 응답.

- [ ] **Step 4: 임시 코드 되돌리기(커밋 금지)**

Run:
```bash
rm -rf src/main/java/com/pinlog/pinlogback/sample
git status --short   # sample/ 흔적이 없어야 함(커밋된 적 없음)
```
Expected: `sample/` 디렉토리 삭제, git에 남은 것 없음. (bootRun은 Ctrl+C 또는 프로세스 종료로 중지)

---

## 완료 조건 요약

- [ ] Task 1~3 커밋됨: springdoc 의존성 + 스모크 테스트, OpenApiConfig, prod-off 설정.
- [ ] `./gradlew clean check --no-daemon` BUILD SUCCESSFUL.
- [ ] 앱 기동 후 `/api/core/swagger-ui.html` 렌더링, `/api/core/v3/api-docs` 200 (수동 확인).
- [ ] Task 4의 임시 컨트롤러는 커밋되지 않았음.
- [ ] PR 본문 "Jira (필수)"에 `S15P11A705-24` 기입, dev 대상.
