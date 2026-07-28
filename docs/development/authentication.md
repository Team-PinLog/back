# 인증 PR 계약

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 **인증·인가 기능을 도입하는 PR이 반드시 만족해야 할 계약**을 정의합니다.

공용 계약 원본은 `Team-PinLog/docs`의 [11_인증_설계](https://github.com/Team-PinLog/docs/blob/main/static/11_인증_설계.md)와 [08_API_명세 §1](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)입니다. 백엔드가 감수하는 것과 재검토 트리거는 [BD-21](../backend/decisions/BD-21-auth-token-model.md)·[BD-22](../backend/decisions/BD-22-signup-commit-point.md)에 있습니다. 이 문서는 그 계약을 **PR이 지켜야 할 형태로** 옮긴 것이며, 공용 계약과 충돌하면 공용 계약이 이깁니다.

## 확정된 사실 (구현 전에 알아야 할 것)

| 항목 | 값 |
| --- | --- |
| 토큰 전달 | `HttpOnly`+`Secure`+`SameSite=Lax` **쿠키**. Bearer 헤더를 쓰지 않고, **응답 본문에 토큰을 담지 않습니다** |
| 기본 경로 | `/api/core/v1` — context path(`/api/core`)에 컨트롤러가 `/v1`을 직접 붙입니다([API 규약](api-conventions.md)) |
| Refresh 쿠키 범위 | `Path=/api/core/v1/auth` — 일반 API 요청에 실리지 않습니다 |
| CSRF | `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더. 상태를 바꾸는 요청에 필수 |
| 가입 확정 | 소셜 인증 성공이 곧 가입 완료. 약관은 로그인 시작 이전(클라이언트)이며 **서버에 동의 API가 없습니다**([BD-22](../backend/decisions/BD-22-signup-commit-point.md)) |

**상태 코드는 용도가 갈려 있습니다**([08_API_명세 §1](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)). 섞으면 클라이언트 분기가 깨집니다.

| 상태 | 이 코드만 쓰는 경우 |
| --- | --- |
| `401` | 인증 실패 — 쿠키 없음·만료, 회전 전 Refresh 재사용 |
| `403` | **CSRF 토큰 누락·불일치 전용** |
| `404` | 리소스 없음 **또는 자원 접근 권한 실패**(존재 여부를 노출하지 않음, [BD-13](../backend/decisions/BD-13-public-boundary-query-dto-split.md)) |

## 배경 — 인증은 실수로 빠진 것이 아니다

backend foundation reset은 Spring Security, OAuth, 임시 계정, `SecurityConfig`를 **의도적으로 제거**했습니다. 인증 없이도 서비스가 실행·테스트·배포되도록 기반을 먼저 정리하기 위함입니다. 따라서 현재:

- 매핑되지 않은 URL은 `401/403`이 아니라 `404`를 반환합니다(`DeploymentContractTests`가 이를 검증).
- `global/security`, `global/config/SecurityConfig`, `domain/auth` 패키지는 **아직 만들지 않습니다**([패키지 구조 규약](package-structure.md)).

인증은 준비되면 **하나의 PR**로 의존성·계약·설정·테스트·문서를 함께 병합합니다. 조각내어 병합하지 않습니다.

## 단일 PR 원칙

인증 PR은 다음을 **모두 포함해야** 병합할 수 있습니다. 하나라도 빠지면 병합하지 않습니다.

1. Spring Security와 OAuth2 Client 의존성
2. principal 계약 — 인증 주체를 컨트롤러가 받는 방식
3. 보안 설정 — 공개/보호 경로, 인가 규칙, 쿠키 속성, CSRF 검증
4. envelope 경계 — 인증 엔드포인트 성공 응답에 본문을 만들지 않는 것과, Security entry point의 오류 envelope
5. 로컬 개발 방법 — 인증을 로컬에서 어떻게 통과시키는지
6. 테스트 — 성공, `401` 미인증, `404` 권한, `403` CSRF, 쿠키 속성
7. 문서 — 이 문서와 [API 규약](api-conventions.md)의 갱신

## 1. 의존성

Security 의존성은 인증 PR에서 처음 추가합니다. 그 전에는 `build.gradle`에 넣지 않습니다.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
// 소셜 로그인(Google·Kakao·Naver)이 확정이므로 선택이 아니라 필수다
implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
testImplementation 'org.springframework.security:spring-security-test'
```

## 2. principal 계약

컨트롤러가 인증 주체를 받는 방식을 **하나로 고정**하고 문서화합니다. Entity를 그대로 principal로 노출하지 않습니다([API 규약](api-conventions.md)의 DTO 분리 원칙).

- 인증 주체 식별자는 `member` 도메인의 식별자(예: `memberId`)와 매핑합니다.
- 인증이 필요한 엔드포인트는 익명 요청에서 principal에 의존하지 않도록 방어합니다.
- **개인 API는 사용자 식별자를 query·body·경로로 받지 않습니다.** 서버가 인증 쿠키로만 식별합니다([BD-14](../backend/decisions/BD-14-identifier-concealment.md)). 파라미터로 받으면 값을 바꿔 남의 데이터를 요청하는 경로가 열립니다.
- principal 해석 실패(쿠키 없음/만료)는 **401**입니다. **자원 접근 권한 실패는 403이 아니라 404**입니다(위 상태 코드 표). `403`은 CSRF 실패에만 씁니다.

## 3. 보안 설정과 경로

`SecurityConfig`는 `global/config`에, 관련 필터·유틸은 `global/security`에 둡니다([패키지 구조 규약](package-structure.md)).

- 서비스 context path는 `/api/core`이고 컨트롤러가 `/v1`을 붙여 **기본 경로는 `/api/core/v1`** 입니다. 보안 설정의 경로 매칭도 이 기준을 따릅니다([API 규약](api-conventions.md), [infra/backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)).
- **공개 경로**를 명시적으로 허용하고, 나머지는 인증을 요구합니다. 최소 다음은 공개로 유지합니다.
  - `/api/core/actuator/health`, `/api/core/actuator/prometheus` — 헬스체크·모니터링이 깨지면 배포가 실패합니다.
  - `/api/core/v1/auth/{provider}/login`, `/api/core/v1/auth/{provider}/callback` — 로그인 진입점 자체가 인증을 요구하면 로그인이 불가능합니다.
- OAuth 콜백 URL은 context path와 `/v1`을 **모두** 포함합니다(`/api/core/v1/auth/{provider}/callback`). 어느 한쪽을 떼면 리다이렉트·OAuth 콜백·Swagger가 깨지고, 공급자 콘솔에 등록한 URL과도 어긋납니다.
- Refresh 쿠키의 `Path=/api/core/v1/auth` 범위를 지킵니다. 재발급과 로그아웃이 이 범위 안에 있어야 쿠키가 전송되고, 탈퇴(`DELETE /api/core/v1/me`)는 범위 밖이라 **Access 쿠키로 식별**합니다.

### 공통 응답 envelope의 예외

인증 엔드포인트의 **성공 응답에는 envelope가 적용되지 않습니다.** 공용 계약이 정한 응답이 전부 본문이 없기 때문입니다 — 콜백은 `302`+`Set-Cookie`, 재발급과 로그아웃은 `204`입니다([08_API_명세 §3.2~3.4](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)).

**그래서 제외 장치를 따로 만들 필요가 없습니다.** `global/web/ApiResponseBodyAdvice`는 `body == null`이면 그대로 `null`을 반환하므로, 본문 없는 응답은 `domain` 패키지 컨트롤러여도 감싸지지 않습니다(`ApiResponseBodyAdviceTest`의 `voidResponseHasEmptyBody`·`noContentEntityHasEmptyBody`가 이 동작을 고정합니다). advice의 판정 조건을 건드리지 마세요 — 도메인 전체의 envelope 계약이 흔들립니다.

거꾸로 말하면 **인증 엔드포인트가 성공 응답에 본문을 만드는 순간 envelope가 적용됩니다.** 본문을 만들지 않는 것이 계약이고, 특히 토큰을 본문에 담지 않는 것은 쿠키를 택한 이유 그 자체입니다([BD-21](../backend/decisions/BD-21-auth-token-model.md)).

오류 응답은 반대로 **envelope를 지켜야** 합니다(§1.5). `GlobalExceptionHandler`를 타는 예외는 자동으로 지켜지지만, Spring Security의 401·403 entry point·handler는 `@RestControllerAdvice` **밖**이라 핸들러를 거치지 않습니다. 이 컴포넌트들은 `ApiResponse.fail(...)`을 직접 만들어 써야 합니다([에러 처리 규약](error-handling.md)).

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
| 미인증 요청(쿠키 없음·만료) | **401** |
| 남의 자원 접근 | **404** — 403이 아닙니다. 존재 여부를 노출하지 않습니다([BD-13](../backend/decisions/BD-13-public-boundary-query-dto-split.md)) |
| 상태 변경 요청에 `X-XSRF-TOKEN` 누락·불일치 | **403** |
| 회전 전 Refresh 재사용 | **401** |
| 공개 경로(health/prometheus, 로그인 진입점) | 인증 없이 접근 가능 |

`403`과 `404`를 정책 없이 섞지 않습니다. 위 표가 정책이며, 각 행에 테스트가 하나씩 대응해야 합니다.

쿠키 자체도 계약이므로 함께 검증합니다.

- 발급 응답의 `Set-Cookie`에 `HttpOnly`·`Secure`·`SameSite=Lax`가 있고, Refresh는 `Path=/api/core/v1/auth`인지
- **응답 본문에 토큰 문자열이 없는지** — 쿠키를 택한 이유가 여기 있으므로 회귀로 고정합니다
- 로그아웃 후 같은 Refresh 쿠키로 재발급이 `401`인지(회전·무효화가 실제로 동작하는지)

DB가 필요한 인증 테스트는 PostgreSQL Testcontainers를 사용합니다(H2 금지).

## 6. 문서 갱신

인증 PR은 이 문서를 실제 구현에 맞게 갱신하고, 인증이 포함된 API는 [API 규약](api-conventions.md)의 오류 계약(`code`/`message`/`traceId`)과 상태 코드를 함께 문서화합니다.

## 완료 조건 체크리스트

- [ ] Security(및 필요 시 OAuth) 의존성 추가
- [ ] principal 계약 정의·문서화 (Entity 직접 노출 금지, 사용자 식별자를 파라미터로 받지 않음)
- [ ] `SecurityConfig`와 공개/보호 경로 설정, health·prometheus·로그인 진입점 공개 유지
- [ ] 쿠키 속성(`HttpOnly`·`Secure`·`SameSite=Lax`)과 Refresh `Path` 범위 구현
- [ ] CSRF 검증(`XSRF-TOKEN` → `X-XSRF-TOKEN`) 구현
- [ ] 인증 엔드포인트 성공 응답에 본문 없음(제외 장치를 만들지 않음) + Security entry point의 오류 envelope 직접 생성
- [ ] 로컬 개발·테스트에서 인증 통과 방법 문서화
- [ ] 성공 / 401 / 404(권한) / 403(CSRF) / 공개 경로 / 쿠키 속성 / 본문 토큰 부재 테스트
- [ ] `./gradlew clean check --no-daemon` 통과
- [ ] 이 문서와 API 규약 갱신
