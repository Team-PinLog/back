# BT-04. `logged_in` 쿠키가 프론트에서 읽히지 않음 — `Path`가 API 경로로 좁혀져 있었다

- **상태**: 해결됨
- **발견**: 2026-07-28, 실제 Google 로그인 수동 검증 중 (Jira 작업)
- **관련**: [BD-21](../decisions/BD-21-auth-token-model.md) · [BI-18](../implements/BI-18-2026-07-28-jwt-cookie-session.md)

## 증상

실제 Google 계정으로 로그인을 마친 뒤 브라우저 콘솔에서 `document.cookie`를 확인했는데 `logged_in`이 없었다. 다른 쿠키(`XSRF-TOKEN`, 그리고 프론트 개발 서버가 심은 `ajs_anonymous_id`)는 보였다.

서버 쪽은 정상이었다 — `core.social_account`에 행이 생기고 Redis에 Refresh 토큰이 저장됐다. 즉 로그인은 성공했고 쿠키도 내려갔는데 **JS가 못 읽는** 상태였다.

## 원인

`AuthCookies`가 `logged_in`에 `Path=/api/core`(context path)를 주고 있었다. Access 쿠키와 같은 값을 쓴 것이다.

쿠키는 **현재 페이지 경로가 `Path` 아래일 때만** `document.cookie`에 나타난다. 프론트 페이지는 `/`·`/auth/callback`·`/feed`처럼 루트 아래에서 서비스되고 `/api/core` 아래에서 실행되는 일이 없다. 따라서 이 쿠키는 **어떤 프론트 페이지에서도 읽을 수 없었다.**

`logged_in`의 존재 이유가 "JS가 읽는 UI 힌트"(BD-21)인데, 그 목적을 달성할 수 없는 상태로 발급되고 있었다.

## 왜 테스트가 잡지 못했나

`AuthTokenContractTests`가 이렇게만 검증하고 있었다.

```java
assertThat(loggedIn).doesNotContain("HttpOnly");
assertThat(loggedIn).contains("Secure").contains("SameSite=Lax");
```

**`HttpOnly`가 아니라는 것은 읽을 수 있다는 뜻이 아니다.** `HttpOnly`는 "JS에게 숨기는가"를, `Path`는 "어느 페이지에서 보이는가"를 정한다. 둘 다 통과해야 읽히는데 앞의 하나만 보고 있었다.

Access·Refresh는 서버가 읽는 쿠키라 `Path`를 헤더 문자열로만 확인해도 충분했다. `logged_in`만 **읽는 주체가 브라우저 JS**여서 같은 검증으로는 부족했는데, 세 쿠키를 한 묶음으로 취급해 그 차이를 놓쳤다.

HTTP 경계 테스트로는 원리적으로 잡기 어렵다 — `Set-Cookie` 헤더에는 문제가 없었고, 문제는 **브라우저가 그 헤더를 어떻게 해석하는가**에 있었다.

## 해결

`logged_in`의 `Path`를 `/`로 바꿨다. 세 쿠키의 `Path` 기준을 **"누가 읽어야 하는가"**로 정리해 `AuthCookies` javadoc에 남겼다.

| 쿠키 | `Path` | 읽는 주체 |
|---|---|---|
| `access_token` | `/api/core` | 서버 — 모든 API 요청 |
| `refresh_token` | `/api/core/v1/auth` | 서버 — 재발급·로그아웃만 |
| `logged_in` | `/` | **프론트 JS** |

## 재발 방지

- 회귀 테스트에 `Path` 단언을 추가했다(`attribute(loggedIn, "Path")`가 `/`). 고치기 전 `expected "/" but was "/api/core"`로 실패하는 것을 먼저 확인했다.
- **읽는 주체가 브라우저 JS인 쿠키를 새로 만들 때는 `Path`를 함께 정한다.** `HttpOnly`만 끄면 된다고 생각하는 것이 이 버그의 원인이었다.
- 실제 브라우저 검증 없이는 이 종류를 잡을 수 없다. 인증 흐름을 바꿀 때 [인증 계약](../../development/authentication.md) §8의 로컬 절차를 한 번 돌린다.
