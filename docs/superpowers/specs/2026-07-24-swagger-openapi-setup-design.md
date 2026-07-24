# Swagger/OpenAPI(springdoc) 설정 설계

- **Jira**: [S15P11A705-24](https://ssafy.atlassian.net/browse/S15P11A705-24)
- **작성일**: 2026-07-24
- **관련 규약**: [api-documentation.md](../../development/api-documentation.md), [configuration.md](../../development/configuration.md), [package-structure.md](../../development/package-structure.md)

## 배경

`back/`(pinlog-back)는 Spring Boot 4.1 기반이나 현재 API 문서화 도구가 붙어 있지 않다.
`api-documentation.md`는 springdoc-openapi로 런타임에 OpenAPI 문서와 Swagger UI를 생성하는
규약을 정의만 해두었고, 실제 코드 반영은 없다. 이 작업은 그 규약을 **최소 인프라 수준으로 코드화**한다.

현 시점 컨트롤러가 0개이므로 OpenAPI 문서의 `paths`는 비어 있다. 이는 정상이며, 컨트롤러가
추가되면 Swagger UI에 자동 반영된다.

## 목표 / 비목표

**목표 (이번 범위)**
- springdoc-openapi 의존성 도입 및 Swagger UI / OpenAPI 문서 노출
- OpenAPI 메타데이터(title/version/description) 정의
- 환경별 노출 제어: local·dev = on, prod = off
- 기동 시 OpenAPI 문서가 정상 생성되는지 자동 검증

**비목표 (이번 범위 밖)**
- 실제 도메인 컨트롤러·DTO·전역 에러 핸들러 구현
- 엔드포인트별 OpenAPI 애노테이션(오류 계약 `code`/`message`/`traceId`, pagination 예시) — 컨트롤러 도입 시 함께
- 인증 도입 후 Swagger 경로 보호 정책 — 인증 PR 이후 ([authentication.md](../../development/authentication.md))

## 아키텍처 / 구성요소

| 구성요소 | 위치 | 내용 |
| --- | --- | --- |
| 의존성 | `build.gradle` | `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.x` (Boot 4.1 호환 최신 3.0.x 핀) |
| OpenAPI 메타 빈 | `global/config/OpenApiConfig.java` (신규) | `@Configuration` + `@Bean OpenAPI` — title `PinLog Core API`, version(= `build.gradle`의 프로젝트 버전 `0.0.1-SNAPSHOT`), description |
| 기본 설정 | `application.yml` | springdoc 기본 on (별도 명시 불필요, 기본값 유지) |
| 운영 오버라이드 | `application-prod.yml` (신규) | `springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false` |

`OpenApiConfig`는 `global/config` 패키지의 첫 클래스다 ([package-structure.md](../../development/package-structure.md) — 실제 클래스가 생길 때 패키지 생성).

### 경로 (context-path 하위)

서비스 context-path는 `/api/core`이며 springdoc 경로도 그 하위로 자동 해석된다.

| 용도 | 경로 |
| --- | --- |
| Swagger UI | `/api/core/swagger-ui.html` |
| OpenAPI 문서(JSON) | `/api/core/v3/api-docs` |

## 노출 제어 흐름 (접근 A — 프로파일 게이팅)

- 로컬·테스트는 현재 **활성 프로파일 없이** 구동 → 기본값(on)이 적용되어 Swagger 노출.
- 운영은 `SPRING_PROFILES_ACTIVE=prod` → `application-prod.yml`이 springdoc을 명시적으로 off.
- "prod에서 명시적으로 끔"이 안전 기본값이다: 프로파일 미설정 환경은 로컬로 간주되어 켜지고,
  운영은 배포 시 prod 프로파일이 확실히 끈다.

대안(환경변수 토글, 애노테이션-only)은 각각 "기본값 노출이 안전하지 않음", "설정의 문서화된
위치(global/config) 미사용"의 이유로 채택하지 않는다.

## 가시성 확인용 컨트롤러 (로컬 전용, 커밋하지 않음)

Swagger UI에 실제 엔드포인트가 렌더링되는지 눈으로 확인하기 위한 임시 수단.

- `package-structure.md`는 실제 도메인(member/place/record/collection/follow/feed)만 허용하고
  샘플 패키지를 금지하므로, 더미 컨트롤러는 **본질적으로 임시**다.
- 따라서 로컬에서만 임시로 추가해 확인하고 **커밋하지 않는다**(작업 후 되돌림).
- 확인용 형태: `GET /samples/ping` → `{"message":"pong","serverTime":"<ISO-8601 UTC>"}`,
  응답은 DTO record로 분리, `@Tag`/`@Operation` 애노테이션으로 컨벤션 시연.
- 커밋되는 산출물에는 이 컨트롤러가 포함되지 않는다 → dev 브랜치는 Swagger 인프라만 깨끗하게 반영.

## 테스트

**신규 스모크 테스트** (커밋 대상): 무프로파일 컨텍스트에서
`GET /api/core/v3/api-docs` → `200`이고 body가 유효한 OpenAPI 문서(`"openapi"` 버전 필드 포함)임을 검증한다.
컨트롤러가 0개라 `paths`가 비어도 통과해야 한다. (더미 컨트롤러는 커밋 안 하므로 `/samples/ping`을 단언하지 않는다.)

**기존 계약 유지**: `DeploymentContractTests`의 4개 테스트가 그대로 통과해야 한다.
특히 `/api/core/not-found` → `404`(springdoc은 문서 경로만 매핑, catch-all 없음),
`/actuator/health`(context-path 밖) → `404`.

**prod 노출 off**: prod 프로파일 통합 테스트는 DB 자격증명 주입 등으로 컨텍스트 로딩이 실패할 수 있어
자동 테스트에서 제외한다. 대신 이 문서에 정책을 남기고 수동으로 확인한다.

DB가 필요한 테스트는 PostgreSQL Testcontainers를 사용한다 ([testing-conventions](../../development/testing-conventions.md)).

## 검증 (완료 조건)

- [ ] `./gradlew clean check --no-daemon` 통과 (Checkstyle Naver `maxWarnings=0` 포함)
- [ ] 앱 기동 후 `/api/core/swagger-ui.html` 렌더링, `/api/core/v3/api-docs` → 200
- [ ] 신규 스모크 테스트 통과, 기존 `DeploymentContractTests` 통과 유지
- [ ] (수동) prod 프로파일에서 문서 경로 비노출 확인
- [ ] (로컬 수동) 임시 SampleController로 Swagger UI에 엔드포인트 렌더링 확인 후 되돌림

## 리스크

- **springdoc 3.0.x ↔ Spring Boot 4.1 minor 호환**: springdoc 3.0.0이 Boot 4.0.0 기준으로
  릴리스됨. Boot 4.1과의 minor 호환은 빌드·기동·`/v3/api-docs` 200으로 검증한다.
  호환 실패 시 상위 springdoc 릴리스를 추적하거나 이 작업을 보류한다.
- **Checkstyle**: 새 `OpenApiConfig`가 Naver 컨벤션(import 순서·공백 등)을 위반하지 않도록 한다.
- **더미 컨트롤러 잔존**: 커밋 전 반드시 제거되었는지 확인한다.
