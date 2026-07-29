# BD-30. 인가 요청(state·PKCE verifier)을 세션이 아니라 쿠키에 담는다

- **상태**: Accepted
- **날짜**: 2026-07-28 (`7f1e8ed`·`1ed1dbe` 구현 시점)
- **작성 시점**: 2026-07-28 — 결정 이후에 정리(같은 날, 미푸시 커밋 점검 중 기록 누락 발견)
- **관련**: S15P11A705-63 · `7f1e8ed`(Security 도입) · `1ed1dbe`(로그인 진입) · `CookieOAuth2AuthorizationRequestRepository`
- **공용 계약**: [11_인증_설계 §2](https://github.com/Team-PinLog/docs/blob/main/static/11_인증_설계.md) (인증 상태를 쿠키에 둔다 — 수용 기록은 [BD-21](BD-21-auth-token-model.md))

## 맥락

공용 계약이 인증 상태를 쿠키로 정했고([BD-21](BD-21-auth-token-model.md)), `SecurityConfig`는 `SessionCreationPolicy.STATELESS`를 선언한다.

그런데 `oauth2Login`의 기본 `AuthorizationRequestRepository` 구현은 `HttpSessionOAuth2AuthorizationRequestRepository`이고, 이름 그대로 `HttpSession`을 만든다. `state`와 PKCE `code_verifier`를 인가 요청 시점부터 콜백까지 들고 있어야 하기 때문이다. **STATELESS 선언과 기본 구현이 정면으로 어긋난다.** 로그인 왕복 동안만 필요한 이 상태를 어디에 둘지 정해야 했다.

> **[BD-21](BD-21-auth-token-model.md)의 "토큰을 쿠키로"와 다른 결정이다.** BD-21은 로그인이 끝난 뒤 **클라이언트가 자기를 증명하는 수단**(Access·Refresh)을 정하고, 그 값은 공용 계약이 준 것이다. 이 문서는 로그인 왕복 **중에만** 존재하는 **서버의 임시 메모**를 어디 둘지 정하며, 브라우저는 내용을 해석하지 않고 보관만 한다. 계약에 규정이 없어 백엔드가 고른 결정이므로 BD로 남긴다. 다만 `Path=/api/core/v1/auth`가 Refresh 쿠키와 같은 것은 우연이 아니라 같은 이유다 — 일반 API 요청에 실리지 않게.

보관 대상에 비밀값이 포함된다는 점이 판단에 영향을 줬다. 기본 resolver는 client secret을 가진 confidential client에는 PKCE를 붙이지 않으므로 `OAuth2AuthorizationRequestCustomizers.withPkce()`로 명시적으로 켰고(RFC 9700), 그 결과 `code_verifier`가 보관 대상에 들어온다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 기본값 유지 — `HttpSession` | 추가 코드 없음. Spring이 검증한 경로 | STATELESS 선언이 무의미해지고 `JSESSIONID`가 발급된다. 다중 파드에서 sticky session이나 세션 저장소가 필요해진다 |
| **(b) 쿠키 + Java 직렬화 + 역직렬화 허용목록** | 세션이 생기지 않는다. Spring이 만든 `OAuth2AuthorizationRequest`를 그대로 보관해 필드 누락 위험이 없다 | 쿠키가 커진다. Java 직렬화 형식에 묶인다 |
| (c) 쿠키 + JSON(필요 필드만) | 형식을 우리가 소유하므로 Security 업그레이드에 깨지지 않는다. 크기가 작다 | `OAuth2AuthorizationRequest` 재구성 코드를 직접 유지해야 하고, 필드 누락이 조용한 버그가 된다 |
| (d) Redis에 저장하고 쿠키에는 키만 | 쿠키가 작고, 서버가 폐기·감사를 통제할 수 있다 | 로그인 진입 자체가 Redis 가용성에 묶인다 |

## 결정

**(b)를 채택한다. 능동적 선택.** 결정적 이유는 둘이다.

1. 세션을 만들지 않아 STATELESS 선언과 파드 수평 확장 전제를 지킨다.
2. 값의 수명과 범위가 좁다 — 180초, `Path=/api/core/v1/auth`. 일반 API 요청에는 실리지 않는다.

(d)를 고르지 않은 이유: Refresh 토큰 저장으로 Redis를 이미 쓰지만([BD-21](BD-21-auth-token-model.md)), **로그인 진입 가능 여부를 Redis 장애에 묶는 대가**가 이 짧은 상태를 서버가 통제해서 얻는 값보다 크다고 봤다.

역직렬화는 허용 클래스를 제한한다. 제한 없이 `readObject`하면 조작된 쿠키로 임의 클래스를 만들어내는 gadget 공격 표면이 열린다.

```java
ObjectInputFilter.Config.createFilter(
    "org.springframework.security.oauth2.core.**;java.util.**;java.lang.**;!*")
```

## 결과

**감수하는 것**

- **쿠키를 서명·암호화하지 않는다.** 무결성은 ① 역직렬화 허용목록 ② 공급자가 돌려준 `state`와 저장값의 비교가 담당한다. 조작된 값은 역직렬화 실패 → `null` → Spring의 `authorization_request_not_found` → 실패 핸들러로 흐른다(fail-closed).
- **서명을 해도 login CSRF는 막히지 않는다.** 공격자가 자기 인가 요청을 정상적으로 발급받아 피해자 브라우저에 심는 시나리오는 서버가 서명한 쿠키로도 성립한다. 완화 수단은 `SameSite=Lax`·`Path` 제한·180초 수명이며, 서명을 생략한 실제 대가는 무결성이 아니라 아래 항목이다.
- **역직렬화 필터에 `maxdepth`·`maxbytes`를 두지 않았다.** 허용 패키지(`java.util.**`)만으로 원격 코드 실행 gadget은 알려진 것이 없지만, 중첩 컬렉션으로 CPU를 소모시키는 payload는 구성할 수 있다. 피해자 브라우저에 쿠키를 심을 수 있는 상대에 한정되고 인증 경로에만 실리므로 지금은 감수한다.
- **Java 직렬화 형식에 묶인다.** Spring Security 업그레이드로 `OAuth2AuthorizationRequest`의 직렬화 형식이 바뀌면 그 시점에 왕복 중이던 로그인이 깨진다(사용자 재시도로 복구). 구·신 파드가 섞이는 rolling update 구간도 같다.
- **`Secure`를 `request.isSecure()`에 맡긴다.** 로컬 http 개발에서 쿠키가 돌아오게 하려는 선택이다. 운영은 Traefik 뒤에서 `forward-headers-strategy: framework`로 https가 인식된다 — 이 설정이 빠지면 운영에서 `Secure`가 붙지 않는다.

**아직 회귀로 고정되지 않은 것**

현재 테스트는 인가 요청에 `code_challenge_method=S256`이 붙는 것과 로그인 왕복에서 `JSESSIONID`가 발급되지 않는 것을 확인한다(`SocialLoginRedirectTests`·`GoogleLoginCallbackTests`). 반면 **쿠키 속성(`HttpOnly`·`SameSite`·`Path`)과 조작된 쿠키의 fail-closed 동작은 테스트가 없다.** 위 감수 항목이 근거로 삼는 성질이므로 후속으로 고정해야 한다.

**재검토 트리거**

- Spring Security 메이저 업그레이드 → 직렬화 호환을 먼저 확인하고, 깨지면 (c) JSON으로 옮긴다.
- 인가 요청에 담을 값이 늘어 쿠키가 4KB에 근접하면 → (d) Redis + 키 쿠키로 옮긴다.
- 로그인 진입에 서버측 폐기나 감사가 필요해지면(예: 동시 로그인 시도 제한) → 쿠키만으로는 불가능하므로 (d).
