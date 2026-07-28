# BI-08. 쿠키 기반 세션 JWT — 발급·검증·회전

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-63
- **근거 결정**: [BD-21](../decisions/BD-21-auth-token-model.md)(토큰 모델) · [BD-29](../decisions/BD-29-jwt-rs256-key-management.md)(알고리즘·키 관리)
- **계약**: [인증 PR 계약](../../development/authentication.md)

## 무엇을 만들었나

`OAuthLoginSuccessHandler`가 회원만 확정하고 쿠키 없이 리다이렉트하던 자리(`TODO(S15P11A705-63)`)를 채웠다. 그 한 줄이 **OAuth Client 역할의 끝**과 **BFF·리소스 서버 역할의 시작** 사이 경계였다.

| 역할 | 산출 |
| --- | --- |
| 발급 | `JwtTokenProvider`(RS256 서명·검증), `JwtKeyProvider`(키 공급), `AuthCookies`(쿠키 3종) |
| 검증 | `JwtAuthenticationFilter`(Access 쿠키 → SecurityContext), `LoginMemberArgumentResolver`·`MemberPrincipal`·`@LoginMember` |
| 회전 | `RefreshTokenStore`(Redis, `jti`당 키), `AuthTokenService`, `AuthTokenController`(`/v1/auth/refresh`·`/logout`) |
| 부수 | `CsrfCookieFilter`, `UnauthorizedException`, `JwtProperties`, `WebMvcConfig` |

## 판단한 것

**공개키를 따로 받지 않는다.** PKCS#8 CRT 개인키가 modulus와 public exponent를 이미 들고 있어 설정 항목을 둘로 늘릴 이유가 없었다. 대신 CRT가 아닌 키가 들어오면 명시적으로 실패한다.

**`kid`를 thumbprint에서 뽑는다.** 난수로 만들면 같은 키를 쓰는 파드끼리 서로 다른 `kid`를 달게 되어, 나중에 회전을 붙일 때 이 값이 쓸모없어진다. 회전 자체는 이번 범위가 아니지만 헤더 자리는 지금 잡아 뒀다.

**Refresh를 `jti`당 키로 저장한다.** 회원당 한 개로 두면 두 기기 로그인 시 한쪽 재발급이 다른 쪽을 끊는다. 소비는 `delete`의 반환값으로 판정한다 — 조회 후 삭제로 나누면 두 요청이 같은 토큰을 동시에 통과시킬 수 있고, 그건 회전 전 재사용을 잡으려는 목적과 정면으로 어긋난다.

**로그아웃을 멱등하게 뒀다.** 이미 무효인 토큰이어도 204이고 쿠키는 항상 지운다. 401을 내면 클라이언트가 쿠키를 못 지운 채 남는다.

**검증 실패를 필터에서 401로 만들지 않는다.** 컨텍스트를 비워 둔 채 통과시키면 인가 규칙이 `RestAuthenticationEntryPoint`로 넘긴다. 오류 응답 형식이 한 곳에만 있어야 envelope 계약이 어긋나지 않는다.

## 구현하다 드러난 것

### `CsrfConfigurer.spa()`만으로는 클라이언트가 CSRF 토큰을 얻을 수 없다

`SpaCsrfTokenRequestHandler`는 토큰을 **지연** 로딩한다. 누군가 값을 실제로 꺼내야 저장소가 쿠키를 내려보내는데, 조회 요청은 꺼낼 일이 없어 `XSRF-TOKEN`이 발급되지 않는다. 그러면 클라이언트는 첫 상태 변경 요청에 넣을 토큰을 구할 방법이 없고 영영 403을 받는다.

기존 `SecurityContractTests`는 **음성 경로(토큰 없으면 403)만** 검증하고 있어서 이 구멍이 드러나지 않았다. `/v1/auth/refresh`의 정상 경로를 처음 테스트하면서 잡혔다. `CsrfCookieFilter`를 `CsrfFilter` 뒤에 두어 해석을 강제했다.

### `Filter` 빈은 서블릿 체인에도 자동 등록된다

`JwtAuthenticationFilter`를 `@Component`로 뒀더니 Security 체인 안팎에 두 번 등록됐다. 이 상태로도 테스트는 통과했지만, 체인 밖 인스턴스가 `SecurityContextHolderFilter`보다 먼저 도는 경로가 생기면 뒤이어 빈 컨텍스트로 덮인다 — 순서에 의존하는 잠복 버그다. 빈으로 만들지 않고 `SecurityConfig`가 직접 생성해 체인에만 물렸다.

### 만료에 60초 관용이 있다

nimbus `DefaultJWTClaimsVerifier`의 기본 시계 오차다. 처음에 `-1초` 만료 토큰으로 테스트했다가 통과해서 알았다. 분산 환경에 필요한 관용이라 그대로 두되, **관용이 있다는 사실 자체를 테스트로 고정**했다(`expiryToleratesClockSkew`) — 없애려면 명시적으로 줄여야 한다는 뜻이다.

### 로그인이 Redis에 의존하게 됐다

`GoogleLoginCallbackTests`가 깨져서 드러났다. Postgres만 띄우던 테스트였는데 콜백이 Refresh를 Redis에 저장하게 됐기 때문이다. `PostgresRedisContainerSupport`를 만들어 붙였다. BD-21이 "Redis 장애 시 재발급이 막힌다"로 감수한 범위가 **로그인까지** 넓어진 셈이다.

## 검증

`./gradlew clean check --no-daemon` — **131개 통과, 실패 0.**

| 테스트 | 무엇을 고정하나 |
| --- | --- |
| `AuthTokenContractTests` (9) | 쿠키 속성·Path, 본문에 토큰 없음, principal 왕복, 조작 토큰 401, 회전과 재사용 401, 로그아웃 후 401, 쿠키 만료, Access를 Refresh 자리에 넣기 |
| `JwtTokenProviderTest` (11) | **alg confusion 거부**, 용도 교차 거부, 다른 키·다른 발급자·만료 거부, RS256·`kid`, 시계 오차, `jti` 유일성 |
| `JwtKeyProviderTest` (5) | 임시 키 생성, **운영 fail-fast**, PEM 파싱과 공개키 유도, `kid` 안정성, 깨진 PEM에 키 내용 미노출 |

**RED 확인.** 구현 전 `AuthTokenContractTests`를 HTTP 경계로만 작성해(새 타입 참조 없이) 8개 중 7개가 실제 단언 실패로 떨어지는 것을 먼저 확인했다. 컴파일 실패가 아니라 "쿠키가 안 내려온다"는 실패였다.

가장 값이 큰 테스트는 `hmacSignedTokenWithPublicKeyAsSecretIsRejected`다. 공개키를 HMAC 비밀로 삼아 HS256으로 서명한 토큰을 만들어 거부되는지 본다. 검증기가 토큰 헤더의 `alg`를 따라가는 순간 이게 통과하고, 그게 RFC 8725 §3.1이 막으라는 경로다.

## 남은 것

- **키 회전** — `kid`만 선반영했고 다중 키 검증은 없다. 지금 키를 바꾸면 전면 로그아웃이다.
- **Access 즉시 무효화** — 로그아웃해도 Access는 최대 30분 유효하다. BD-21이 감수한 범위다.
- **404(타인 자원 접근) 테스트** — 소유자가 있는 도메인 리소스가 이 브랜치에 없어 검증 대상이 없다. S15P11A705-67~71이 가져온다.
- **`Team-PinLog/docs`의 `static/08_API_명세.md` 개정** — 별도 저장소라 이 PR 밖이다.
- **인프라에 `JWT_PRIVATE_KEY` 요청** — 운영 배포 전에 Secret이 없으면 파드가 뜨지 않는다.
- **도메인 브랜치 스텁 제거** — S15P11A705-67의 `X-Debug-Member-Id` 리졸버와 `pinlog.auth.stub.enabled`를 병합 시 반드시 버려야 한다. 남으면 운영 인증 우회 구멍이다.
