# BI-08. 쿠키 기반 세션 JWT — 발급·검증·회전

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-63
- **근거 결정**: [BD-21](../decisions/BD-21-auth-token-model.md)(토큰 모델) · [BD-31](../decisions/BD-31-jwt-rs256-key-management.md)(알고리즘·키 관리)
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

## 리팩터 (`b44a6fc`)

동작을 바꾸지 않고 순증 −142줄. 131개 통과는 그대로다.

**앞으로 인증 흐름을 테스트할 때는 `SocialLoginTestSupport`를 상속한다.** 스텁 공급자 기동·설정 덮어쓰기와 로그인 → 인가 → 콜백 리다이렉트 추적이 들어 있다. 이게 없던 동안 `AuthTokenContractTests`와 `GoogleLoginCallbackTests`가 같은 절차를 각자 복제하고 있었고, 콜백 경로나 state 전달 방식이 바뀌면 두 곳을 고쳐야 했다.

다만 **`SocialLoginRedirectTests`는 이 base를 쓰지 않는다.** 그 테스트는 스텁이 아니라 실제 Google 설정으로 인가 URL을 검증하는 것이 목적이라, base를 물리면 스텁 설정이 강제돼 목적이 사라진다.

나머지: `WebUtils.getCookie()`로 수동 쿠키 순회 대체, nimbus `keyIDFromThumbprint()`로 이중 빌드 제거, `sub` 이중 파싱 제거, 테스트의 JSON 정규식을 `SignedJWT.parse()`로, Refresh 쿠키 부재 판단을 컨트롤러에서 `AuthTokenService`로 이동(토큰의 의미는 서비스가 소유한다).

## JSpecify 마킹 확대

[BD-29](../decisions/BD-29-nullmarked-security-package.md)이 `global/security`만 `@NullMarked`로 선언해 둔 상태였다. 그 기준("전수 감사 비용이 작고 다른 파트 소유 파일이 안 섞일 것")을 그대로 적용해 `global/config`와 `domain/auth/{controller,dto,service,exception}`을 추가로 마킹했다.

**마킹하지 않으면 `@Nullable`이 아무 의미도 없다.** `JwtProperties.privateKey`에 표기를 붙여 뒀지만 `global/config`가 미마킹이라 도구가 무시하고 있었다. 이 사실이 `JwtKeyProviderTest`에서 `properties(null)` 경고로 드러났다.

마킹이 드러낸 실제 구멍 셋:

| 위치 | 문제 |
| --- | --- |
| `JwtKeyProvider` 생성자 | `hasPrivateKey()`로 검사하고 `fromPem(properties.privateKey())`에 nullable을 non-null 자리로 넘겼다. 술어 메서드는 검사와 사용의 연결을 컴파일러에 알려 주지 못한다 — 지역 변수 + 흐름 검사로 바꾸고 술어를 지웠다 |
| `OAuthUserInfo.email` | javadoc은 "null이다"라고 적고 타입은 non-null이었다. 거짓 보증 |
| `OAuthUserInfo.providerUserId` | `stringValue(attributes.get("sub"))`(nullable)를 non-null 컴포넌트에 넣고 있었다. `sub`가 없으면 조용히 통과해 `provider_user_id` NOT NULL 위반으로 DB까지 내려가서야 터진다 |

세 번째는 **동작 변경**이다. `requiredStringValue`로 진입점에서 끊는다. Spring이 `user-name-attribute: sub` 설정으로 앞단에서 걸러 주긴 하지만 그 보증이 설정에만 있고 타입에는 없어, 설정이 바뀌면 조용히 뚫린다.

`global/web`·`global/response`·`global/common`은 그대로 뒀다. BD-27이 명시적으로 제외한 범위이고 이 티켓의 산출물도 아니다. BD-27의 재검토 트리거("마킹 패키지가 늘어 혼재가 부담이 될 때 → 레포 전체 도입 논의")에 근접했으나, 전체 도입은 다른 파트 소유 파일까지 감사해야 하므로 이 PR 범위 밖으로 판단했다.

## `global/security` 책임 분리

한 패키지에 14개 클래스가 쌓여 응집이 무너졌다. 책임별로 넷으로 나눴다.

| 패키지 | 책임 | 언제 도는가 |
| --- | --- | --- |
| `oauth` | 공급자와의 OAuth2 흐름 (OAuth 클라이언트 역할) | 로그인 진입·콜백 |
| `token` | 세션 토큰 서명·검증과 쿠키 전달 (BFF 역할) | 로그인 성공·재발급 |
| `authentication` | 요청을 인증 주체로 변환, principal 계약 (리소스 서버 역할) | 모든 요청 |
| `error` | 필터 체인이 직접 만드는 401·403 응답 | 인증·인가 실패 |

경계의 근거는 **역할**이다. 이 티켓 내내 "OAuth Client 역할의 끝과 BFF 역할의 시작"이 어디냐를 따져 왔는데, 그 경계를 패키지로 굳혔다. 발급(`token`)과 검증(`authentication`)을 더 가른 기준은 수명과 호출 빈도다 — 발급은 로그인에 한 번, 검증은 모든 요청에서 돈다.

의존은 단방향임을 확인하고 나눴다: `oauth` → `token`, `authentication` → `token`, `error`는 독립. 역방향이 생기면 경계가 틀린 것이다.

`CsrfCookieFilter`는 루트에 남겼다. CSRF는 세션 토큰과 다른 관심사라 `token`에 넣으면 패키지 이름이 거짓말이 되고, 나머지 셋에도 속하지 않는다. 억지로 끼워 넣는 것보다 "어디에도 안 속한다"를 드러내는 편이 낫다.

**하위 패키지마다 `package-info`에 `@NullMarked`를 다시 선언했다.** 패키지 애노테이션은 상속되지 않으므로, 이걸 빠뜨리면 방금 붙인 마킹이 이동만으로 조용히 사라진다. 컴파일은 통과하기 때문에 눈치채기 어렵다.

## 실제 Google로 한 수동 검증

스텁(`StubOAuthProvider`)이 **구조적으로 덮지 못하는 것**을 보려고 실제 Google 계정으로 한 번 돌렸다. 이 흐름은 동의 화면이 사람의 브라우저를 요구하므로 CI에 넣을 수 없다 — 인증 흐름을 바꿀 때 손으로 하는 절차로 남긴다([인증 계약](../../development/authentication.md) §8).

확인된 것:

- **OIDC 경로가 동작한다.** 테스트는 `scope`에서 `openid`를 빼고 돌아(서명된 id_token·JWKS 검증 우회) `OidcUserService` 경로에 자동 커버리지가 **0건**이었다. 운영 설정은 `openid,email`이다. `sub`·`email`이 정상 추출돼 `core.social_account`에 저장되는 것을 확인했다.
- `redirect_uri` 정확 일치, PKCE `S256`, `nonce` 모두 실제 Google에서 통과.
- `Secure` 쿠키가 `http://localhost`에서 저장·전송된다. 코드 주석에 주장만 해두고 검증한 적 없던 지점이다.
- Redis에 `auth:refresh:{memberId}:{jti}`가 7일 TTL로 쌓인다.

그리고 **버그 하나를 잡았다** — `logged_in` 쿠키를 프론트가 읽을 수 없었다([BT-04](../troubleshooting/BT-04-logged-in-cookie-path-unreadable.md)). `Path=/api/core`로 발급하고 있었고, `HttpOnly`가 아니라는 것만 확인하는 테스트로는 잡히지 않았다. **`HttpOnly`가 아니라는 것은 읽을 수 있다는 뜻이 아니다.**

## 감수한 것 — 장애 때 판단이 빨라지도록

**Redis가 로그인·재발급의 경성 의존이 됐다.** 순단이 나면 콜백과 `POST /v1/auth/refresh`가 실패한다. [BD-28](../decisions/BD-28-readiness-includes-db.md)대로 readiness에는 Redis가 없으므로 **파드는 Ready인데 로그인만 전부 실패하는 상태**가 된다.

이미 발급된 Access는 최대 30분 계속 동작하므로 전면 장애는 아니다 — 로그인한 사용자는 30분간 정상, 새 로그인과 재발급만 막힌다. [BD-21](../decisions/BD-21-auth-token-model.md)이 "Redis 장애 시 재발급이 막혀 세션이 30분 안에 끊긴다"로 적어 둔 범위가 **로그인까지** 넓어진 것이다.

readiness에 Redis를 넣지 않은 판단은 BD-28에 있고 여기서 뒤집지 않는다. 다만 **장애 시 증상이 "파드 정상 + 로그인 불가"라는 것**을 여기 적어 둔다 — 이걸 모르면 원인 찾는 데 시간이 걸린다.

**인가 요청 쿠키가 Java 직렬화를 쓴다.** `OAuth2AuthorizationRequest`의 직렬화 형식은 Spring Security 버전 간 호환이 보장되지 않아, **라이브러리를 올려 배포하는 순간 왕복 중이던 로그인은 전부 실패한다.** 사용자에게는 재로그인으로 끝나므로 치명적이지 않고, 배포 창에 진행 중인 로그인 수도 적다. 필요한 필드만 뽑아 JSON으로 담으면 이 결합이 없어진다 — 후속으로 남긴다([BD-30](../decisions/BD-30-authorization-request-in-cookie.md)).

**공급자 응답이 규격을 벗어나면 우리 실패 경로를 타지 못한다.** 예를 들어 `sub`가 빈 문자열이면 Spring 내부(`OAuth2AuthorizedClient` 생성)에서 `IllegalArgumentException`으로 먼저 죽는다. `AbstractAuthenticationProcessingFilter`는 `AuthenticationException`만 실패 핸들러로 넘기므로 이 예외는 그냥 빠져나가고, 사용자는 `OAUTH_FAILED` 복귀 대신 오류 페이지를 본다. 우리 핸들러 바깥이라 감쌀 자리가 없다. Google이 규격을 지키는 한 발생하지 않는다고 보고 넘어간다.

## 남은 것

- **키 회전** — `kid`만 선반영했고 다중 키 검증은 없다. 지금 키를 바꾸면 전면 로그아웃이다.
- **Access 즉시 무효화** — 로그아웃해도 Access는 최대 30분 유효하다. BD-21이 감수한 범위다.
- **404(타인 자원 접근) 테스트** — 소유자가 있는 도메인 리소스가 이 브랜치에 없어 검증 대상이 없다. S15P11A705-67~71이 가져온다.
- **`Team-PinLog/docs`의 `static/08_API_명세.md` 개정** — 별도 저장소라 이 PR 밖이다.
- **인프라에 `JWT_PRIVATE_KEY` 요청** — 운영 배포 전에 Secret이 없으면 파드가 뜨지 않는다.
- **도메인 브랜치 스텁 제거** — S15P11A705-67의 `X-Debug-Member-Id` 리졸버와 `pinlog.auth.stub.enabled`를 병합 시 반드시 버려야 한다. 남으면 운영 인증 우회 구멍이다.
