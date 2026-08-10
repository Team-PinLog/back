# OAuth 인가·콜백 경로 상수의 소유를 security로 옮겼다

- **날짜**: 2026-08-02
- **추적**: Jira 작업
- **관련**: [back#157](https://github.com/Team-PinLog/back/issues/157) · [package-structure](../../development/package-structure.md) · [BD-31](../decisions/BD-31-jwt-rs256-key-management.md)

구조를 훑다가 `SecurityConfig`가 `domain/auth/controller/SocialLoginController`를 import하는 것을 봤다. 무엇을 쓰는지 따라가 보니 `AUTHORIZATION_BASE_URI` 문자열 하나였다.

## 왜 뒤집었나

그 값의 의미를 확정하는 것은 컨트롤러가 아니라 **필터 체인**이다. `SecurityConfig`가 resolver에 넘긴 baseUri가 `OAuth2AuthorizationRequestRedirectFilter`의 매칭 기준이 되고, 컨트롤러는 명세 경로(`/auth/{provider}/login`)에서 그리로 넘겨주는 소비자다. baseUri를 바꾸면 컨트롤러의 리다이렉트가 404가 되지 그 반대는 성립하지 않는다. 즉 선언과 권위가 어긋나 있었다.

`AuthCookies.REFRESH_TOKEN`이 이미 올바른 방향의 선례다 — `global/security/token`이 이름을 갖고 `AuthTokenController`가 빌려 쓴다. `AUTHORIZATION_BASE_URI`만 거꾸로였다.

**의존 간선이 실제로 줄어드는 경우라는 점이 판단 근거였다.** 같은 세션에서 `AuthTokenService.TokenPair`(`global/security/oauth`가 참조)도 봤지만 그건 두지로 결론냈다 — 그 핸들러는 이미 `AuthTokenService`를 주입받으므로 중첩 타입이 새 간선을 만들지 않는다. 이쪽은 문자열 하나 때문에 없어도 되는 간선을 만든 경우여서 갈랐다.

## 콜백 리터럴을 함께 흡수한 이유

같은 OAuth 경로 계약의 두 짝이 서로 다른 매체에 있었다 — 인가는 컨트롤러의 상수, 콜백은 `SecurityConfig` 안의 인라인 리터럴. 어느 한쪽 선택보다 이 불일치가 나빴다. 다음에 경로를 손대는 사람이 두 짝 중 하나만 고칠 여지를 남긴다.

## `application.yml`에 두는 안을 검토하고 기각했다

BD-39가 `embedding-profile`을 `application.yml` 리터럴로 둔 선례가 있어 그 기준을 이 값에 대봤다. 세 칸이 모두 반대였다 — 환경별로 달라지지 않고, 재배포 없이 바꿀 이유가 없고, 다른 파트와 공유하지도 않는다. BD-39는 **조절 가능한 파트 간 계약값**에 대한 결정이고 이것은 **우리 서블릿 라우팅 상수**다.

결정적인 것은 매체 일관성이었다. 인가 baseUri만 yml로 내리면 `@RequestMapping`·`@GetMapping`·`PUBLIC_AUTH`는 자바에 남아 라우팅이 두 매체로 쪼개진다. 지금 문제로 지적한 "한 계약이 여러 스타일로 흩어짐"이 해소되는 게 아니라 재배치될 뿐이다. `api-conventions.md`가 *"`/v1`을 전역 설정으로 자동 부여하지 않는다 — 버저닝은 컨트롤러의 책임"* 이라고 이미 정한 결과 그대로다. 덧붙여 yml로 두면 `application-prod.yml`에서 덮어써 운영만 깨뜨릴 손잡이가 생기는데, 차이가 없는 값에 그 손잡이는 부채다.

## 처음 제안에서 좁힌 것

`PUBLIC_AUTH`(`/v1/auth/**`)까지 홀더에 모으려 했다가 뺐다. 그것은 `/refresh`·`/logout`까지 덮는 **인가 매처**여서 OAuth 전용 홀더에 넣으면 이름이 거짓이 된다. `security` 루트에 `CsrfCookieFilter`를 억지로 끼워 넣지 않은 것과 같은 판단이다(package-structure).

`application.yml`의 `redirect-uri`는 placeholder를 쓰는 Spring 설정이라 상수로 합칠 수 없다. 두 값이 같은 경로를 가리키는지는 사람이 지키고, 그 사실을 홀더 javadoc에 적었다.

## 검증

동작을 바꾸지 않는 이동이라 새 테스트를 만들지 않았다. **어느 테스트가 이 상수들을 실제로 붙잡는지는 추측하지 않고 일부러 깨뜨려 확인했다.**

| 실험 | 결과 |
|---|---|
| `CALLBACK_BASE_URI`를 `/v1/auth/*/callback-BROKEN`으로 | `GoogleLoginCallbackTests` **3/5 실패**(exit 1) — 이쪽이 게이트다 |
| 같은 조건에서 `SocialLoginRedirectTests` | **통과**(exit 0) — 게이트가 아니다 |
| `AUTHORIZATION_BASE_URI`를 `-MOVED`로 | 두 테스트 **모두 통과**(exit 0) |

**첫 초안에 적었던 "두 값이 어긋나면 `SocialLoginRedirectTests`가 실패한다"는 틀렸다.** 그 테스트는 인가 요청의 `redirect_uri`를 단정하는데, 그 값의 출처는 `CALLBACK_BASE_URI`가 아니라 `application.yml`의 `redirect-uri`다. `CALLBACK_BASE_URI`는 Security가 콜백을 **받을** 경로만 정하고, 그 경로는 이 테스트가 밟지 않는다. 콜백을 실제로 때리는 `GoogleLoginCallbackTests`·`KakaoNaverLoginCallbackTests`가 그것을 고정한다.

셋째 줄도 함께 기록해 둔다. 인가 경로는 생산자와 소비자가 **상수 하나를 공유하므로** 값을 바꾸면 양쪽이 함께 움직여 흐름이 유지된다 — 그 값은 외부에 노출되지 않는 내부 경로이고 자기 정합적이다. 다만 이 성질은 이 커밋이 만든 것이 아니다. 이전에도 `SocialLoginController`의 상수 하나를 `SecurityConfig`가 참조하는 단일 출처였다. **이 변경이 고친 것은 어긋남 위험이 아니라 소유 방향과 그로 인한 import 의존이다.** 그 점을 초안이 흐리게 썼다.

`./gradlew clean check --no-daemon` **BUILD SUCCESSFUL** — 테스트 클래스 67개 · 454건 전부 통과, 실패·오류·건너뜀 0. Checkstyle과 `jacocoTestCoverageVerification`도 같은 호출에 포함됐다. 위 실험 후 소스를 원복해 커밋 상태와 바이트 단위로 같음을 확인한 뒤 다시 돌렸다.

> 이 작업의 검증에는 곁길이 있었다. 로컬에 JDK 21이 없어 툴체인이 안 잡혔고(Temurin 21 설치로 해결, Gradle이 레지스트리로 자동 감지해 `gradle.properties`는 건드리지 않았다), Docker Desktop이 6주 전 잔여 AF_UNIX 소켓을 지우지 못해 기동에 실패했다. 소켓은 `del`·PowerShell·`\\?\` 확장 경로·`FILE_FLAG_OPEN_REPARSE_POINT` 모두 `ERROR_CANT_ACCESS_FILE`로 거부했고, **부모 디렉터리 rename**으로 우회했다(`Docker/run` · `docker-secrets-engine`). 저장소와 무관한 환경 문제라 여기 한 줄로만 남긴다.
