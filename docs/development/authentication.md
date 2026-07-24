# 인증 PR 계약

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 **인증·인가 기능을 도입하는 PR이 반드시 만족해야 할 계약**을 정의합니다.

## 배경 — 인증은 실수로 빠진 것이 아니다

backend foundation reset은 Spring Security, OAuth, 임시 계정, `SecurityConfig`를 **의도적으로 제거**했습니다. 인증 없이도 서비스가 실행·테스트·배포되도록 기반을 먼저 정리하기 위함입니다. 따라서 현재:

- 매핑되지 않은 URL은 `401/403`이 아니라 `404`를 반환합니다(`DeploymentContractTests`가 이를 검증).
- `global/security`, `global/config/SecurityConfig`, `domain/auth` 패키지는 **아직 만들지 않습니다**([패키지 구조 규약](package-structure.md)).

인증은 준비되면 **하나의 PR**로 의존성·계약·설정·테스트·문서를 함께 병합합니다. 조각내어 병합하지 않습니다.

## 단일 PR 원칙

인증 PR은 다음을 **모두 포함해야** 병합할 수 있습니다. 하나라도 빠지면 병합하지 않습니다.

1. Spring Security(및 필요 시 OAuth) 의존성
2. principal 계약 — 인증 주체를 컨트롤러가 받는 방식
3. 보안 설정 — 공개/보호 경로, 인가 규칙
4. 로컬 개발 방법 — 인증을 로컬에서 어떻게 통과시키는지
5. 테스트 — 성공, 401, 403(또는 확정된 리소스 은닉 404)
6. 문서 — 이 문서와 [API 규약](api-conventions.md)의 갱신

## 1. 의존성

Security 의존성은 인증 PR에서 처음 추가합니다. 그 전에는 `build.gradle`에 넣지 않습니다.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
// OAuth를 쓰는 경우에만:
// implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
testImplementation 'org.springframework.security:spring-security-test'
```

## 2. principal 계약

컨트롤러가 인증 주체를 받는 방식을 **하나로 고정**하고 문서화합니다. Entity를 그대로 principal로 노출하지 않습니다([API 규약](api-conventions.md)의 DTO 분리 원칙).

- 인증 주체 식별자는 `member` 도메인의 식별자(예: `memberId`)와 매핑합니다.
- 인증이 필요한 엔드포인트는 익명 요청에서 principal에 의존하지 않도록 방어합니다.
- principal 해석 실패(토큰 없음/만료)는 **401**, 권한 부족은 **403**으로 구분합니다.

## 3. 보안 설정과 경로

`SecurityConfig`는 `global/config`에, 관련 필터·유틸은 `global/security`에 둡니다([패키지 구조 규약](package-structure.md)).

- 서비스 context path는 `/api/core`입니다. 보안 설정의 경로 매칭도 이 기준을 따릅니다([infra/backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)).
- **공개 경로**를 명시적으로 허용하고, 나머지는 인증을 요구합니다. 최소 다음은 공개로 유지합니다.
  - `/api/core/actuator/health`, `/api/core/actuator/prometheus` — 헬스체크·모니터링이 깨지면 배포가 실패합니다.
- OAuth를 쓰는 경우 콜백 URL은 context path(`/api/core`)를 포함합니다. context path를 떼면 리다이렉트·OAuth 콜백·Swagger가 깨집니다.

## 4. 로컬 개발 방법

인증을 켠 뒤에도 로컬에서 개발·테스트가 가능해야 합니다. PR은 다음 중 하나 이상을 문서화합니다.

- 로컬 프로파일에서 테스트용 사용자/토큰을 발급하는 방법, 또는
- 통합 테스트에서 인증을 주입하는 방법(`spring-security-test`의 지원 활용).

로컬에서 인증을 통째로 우회하는 설정은 두지 않습니다. 인증 경로도 테스트 대상입니다.

## 5. 테스트 (필수)

인증·인가 변경은 [테스트 규약](testing-conventions.md)에 따라 다음을 모두 테스트합니다.

| 경우 | 기대 |
| --- | --- |
| 정상 인증 요청 | 성공(2xx) |
| 미인증 요청 | **401** |
| 권한 부족 | **403** (또는 확정된 리소스 은닉 정책이면 **404**) |
| 공개 경로(health/prometheus) | 인증 없이 접근 가능 |

리소스 은닉을 위해 403 대신 404를 반환하는 경우, 그 정책을 이 문서에 명시하고 테스트도 404로 검증합니다. 정책 없이 임의로 섞지 않습니다.

DB가 필요한 인증 테스트는 PostgreSQL Testcontainers를 사용합니다(H2 금지).

## 6. 문서 갱신

인증 PR은 이 문서를 실제 구현에 맞게 갱신하고, 인증이 포함된 API는 [API 규약](api-conventions.md)의 오류 계약(`code`/`message`/`traceId`)과 상태 코드를 함께 문서화합니다.

## 완료 조건 체크리스트

- [ ] Security(및 필요 시 OAuth) 의존성 추가
- [ ] principal 계약 정의·문서화 (Entity 직접 노출 금지)
- [ ] `SecurityConfig`와 공개/보호 경로 설정, health·prometheus 공개 유지
- [ ] 로컬 개발·테스트에서 인증 통과 방법 문서화
- [ ] 성공 / 401 / 403(또는 404 은닉) / 공개 경로 테스트
- [ ] `./gradlew clean check --no-daemon` 통과
- [ ] 이 문서와 API 규약 갱신
