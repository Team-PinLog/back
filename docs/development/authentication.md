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
4. envelope 경계 — 인증 엔드포인트의 opt-out과 Security entry point의 오류 envelope
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

- [x] Security(및 필요 시 OAuth) 의존성 추가
- [x] principal 계약 정의·문서화 (Entity 직접 노출 금지, 사용자 식별자를 파라미터로 받지 않음)
- [x] `SecurityConfig`와 공개/보호 경로 설정, health·prometheus·로그인 진입점 공개 유지
- [x] 쿠키 속성(`HttpOnly`·`Secure`·`SameSite=Lax`)과 Refresh `Path` 범위 구현
- [x] CSRF 검증(`XSRF-TOKEN` → `X-XSRF-TOKEN`) 구현
- [x] ~~envelope opt-out 장치~~ — 불필요함이 확인됐습니다(위 "공통 응답 envelope의 예외"). Security entry point의 오류 envelope는 `SecurityErrorWriter`가 만듭니다
- [x] 로컬 개발·테스트에서 인증 통과 방법 문서화 (아래 §7)
- [x] 성공 / 401 / 403(CSRF) / 공개 경로 / 쿠키 속성 / 본문 토큰 부재 테스트
- [ ] **404(타인 자원 접근)** — 이 브랜치에는 소유자가 있는 도메인 리소스가 없어 검증할 대상이 없습니다. 도메인 API 티켓(S15P11A705-67~71)이 함께 가져옵니다
- [x] `./gradlew clean check --no-daemon` 통과
- [x] 이 문서와 API 규약 갱신
- [ ] **공용 계약(`Team-PinLog/docs`) `static/08_API_명세.md` 개정** — 별도 저장소라 이 PR 밖입니다

## 7. 구현된 형태 (S15P11A705-63)

여기부터는 계약이 아니라 **실제로 이렇게 만들어졌다**는 기록입니다. 계약과 어긋나면 위쪽이 이깁니다.

### 토큰과 키

서명은 **RS256**이고 키 관리 근거는 [BD-29](../backend/decisions/BD-29-jwt-rs256-key-management.md)에 있습니다.

| | |
| --- | --- |
| 발급 | `JwtTokenProvider` — Access·Refresh 모두 `sub`(memberId)·`iss`·`exp`·`jti`·`token_use` |
| 키 공급 | `JwtKeyProvider` — `pinlog.auth.jwt.private-key`(PKCS#8 PEM). 공개키는 개인키에서 뽑습니다 |
| 키 없을 때 | 로컬·테스트는 임시 키쌍 생성, **운영 프로파일은 기동 실패** |
| `kid` | 공개키 thumbprint. 회전은 아직 구현하지 않았고 헤더만 선반영했습니다 |
| 검증 | 허용 알고리즘을 RS256으로 **고정**합니다. 토큰 헤더의 `alg`를 따라가지 않습니다(RFC 8725 §3.1) |

`token_use`로 Access와 Refresh를 구분합니다. 같은 키로 서명하므로 이 구분이 없으면 30분짜리 토큰이 7일짜리 재발급 권한을 갖습니다.

**만료에는 60초의 시계 오차 관용이 있습니다** — nimbus `DefaultJWTClaimsVerifier`의 기본값을 그대로 씁니다. 분산 환경에서 필요한 관용이라 두었고, 그만큼 실제 만료가 늦다는 뜻입니다.

### 쿠키

| 쿠키 | 속성 | Path |
| --- | --- | --- |
| `access_token` | `HttpOnly`·`Secure`·`SameSite=Lax`, 30분 | `/api/core` |
| `refresh_token` | `HttpOnly`·`Secure`·`SameSite=Lax`, 7일 | `/api/core/v1/auth` |
| `logged_in` | `Secure`·`SameSite=Lax`, 7일. **`HttpOnly` 아님** | `/api/core` |

`logged_in`은 UI 힌트 전용입니다. **인가 판단에 쓰지 마세요** — 값이 클라이언트에서 조작 가능합니다.

`Secure`를 로컬에서도 끄지 않습니다. 브라우저는 `http://localhost`를 신뢰할 수 있는 오리진으로 취급해 `Secure` 쿠키를 그대로 보냅니다.

### Refresh 회전

`RefreshTokenStore`가 발급한 `jti`마다 Redis 키를 하나 두고(`auth:refresh:{memberId}:{jti}`), 재발급할 때 **삭제로 소비**합니다. `delete`의 반환값이 곧 검사 결과입니다 — 조회 후 삭제로 나누면 두 요청이 같은 토큰을 동시에 소비할 수 있습니다.

회원당 하나가 아니라 `jti`당 하나인 이유는 다중 기기입니다. 회원당 한 개면 한쪽 재발급이 다른 쪽 세션을 끊습니다.

**로그아웃은 멱등합니다.** 이미 무효인 토큰으로 호출해도 204이고 쿠키는 항상 지웁니다. 여기서 401을 내면 클라이언트가 쿠키를 못 지운 채 남습니다.

### CSRF 토큰을 클라이언트가 얻는 방법

`CsrfConfigurer.spa()`의 토큰은 **지연 로딩**이라 조회 요청만으로는 `XSRF-TOKEN` 쿠키가 내려가지 않습니다. 그러면 클라이언트는 첫 상태 변경 요청에 넣을 토큰을 구할 방법이 없어 영영 403을 받습니다. `CsrfCookieFilter`가 `CsrfFilter` 뒤에서 토큰 해석을 강제해 **아무 요청에나 `XSRF-TOKEN` 쿠키가 실려 나가도록** 합니다.

프론트는 그 쿠키 값을 읽어 상태 변경 요청의 `X-XSRF-TOKEN` 헤더에 넣으면 됩니다.

### principal

`@LoginMember MemberPrincipal`로 받습니다. `LoginMemberArgumentResolver`가 `SecurityContext`에서 꺼내고, 없으면 401입니다(fail-closed).

```java
@GetMapping("/v1/collections")
public CursorPage<CollectionSummaryResponse> listMine(@LoginMember MemberPrincipal me) {
    return collectionService.listMine(me.memberId(), ...);   // 서비스는 Long을 받는다
}
```

> **도메인 브랜치와의 병합 주의.** S15P11A705-67이 같은 이름의 스텁(`X-Debug-Member-Id` 헤더를 읽는 리졸버)을 먼저 만들어 뒀습니다. 병합할 때 **스텁 쪽을 버리고 이 구현을 남겨야 합니다.** 스텁의 헤더 분기와 `pinlog.auth.stub.enabled` 프로퍼티가 남으면 운영 인증 우회 구멍이 됩니다.

## 8. 로컬 개발과 테스트에서 인증 통과하기

인증을 우회하는 스위치는 없습니다. 아래는 모두 **실제 인증 경로를 그대로 타는** 방법입니다.

### 로컬 브라우저

`compose.yaml`의 PostgreSQL·Redis를 띄우고 앱을 실행한 뒤 `http://localhost:8080/api/core/v1/auth/google/login`으로 들어가면 됩니다. `.env`에 `GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET`이 있어야 하고, 없으면 인가 요청 URL 생성까지만 됩니다.

`JWT_PRIVATE_KEY`는 로컬에서 **주지 않아도 됩니다.** 기동할 때 임시 키쌍을 만들고 경고 로그를 남깁니다. 다만 재시작하면 기존 토큰이 전부 무효가 되니 다시 로그인해야 합니다. 고정하고 싶으면 PEM을 만들어 `.env`에 넣으면 됩니다.

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-local.pem
# .env 에 한 줄로: JWT_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"
```

### 통합 테스트

`AuthTokenContractTests`처럼 **로그인 흐름을 실제로 한 번 돌고** 응답의 `Set-Cookie`에서 토큰을 꺼내 씁니다. 공급자는 `StubOAuthProvider`로 대역화하므로 네트워크가 필요 없습니다.

Redis가 필요한 테스트는 `PostgresRedisContainerSupport`를 상속합니다 — 로그인이 Refresh를 Redis에 저장하므로, 콜백을 타는 테스트는 Postgres만으로는 실패합니다.

쿠키를 `CookieManager`에 맡기지 말고 `Set-Cookie` 원문에서 꺼내 `Cookie` 헤더로 직접 넣으세요. Java `CookieManager`는 http 링크에 `Secure` 쿠키를 싣지 않아 왕복이 되지 않습니다.
