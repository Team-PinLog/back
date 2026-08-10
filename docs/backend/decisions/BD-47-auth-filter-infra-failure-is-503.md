# BD-47. 인증 필터의 인프라 실패를 401이 아니라 503 공통 envelope으로 내보낸다

- **상태**: Accepted
- **날짜**: 2026-08-03
- **관련**: Jira 작업 · [back#171](https://github.com/Team-PinLog/back/issues/171) ·
  [BD-41](BD-41-withdrawn-member-access-token.md)(이 문제를 남긴 결정) · [BD-28](BD-28-readiness-includes-db.md)(readiness에 `db`) ·
  [BT-06](../troubleshooting/BT-06-refresh-revocation-leak-under-concurrent-rotation.md) ·
  [11_인증_설계 §4.4](https://github.com/Team-PinLog/docs/blob/main/static/11_인증_설계.md)(401 → 재발급이 클라이언트 계약)

## 맥락

BD-41이 `JwtAuthenticationFilter`에 탈퇴 판정(`memberRepository.isActive`)을 넣으면서 **순수 JWT 검증이던 필터가 처음으로 인프라 사유로 예외를 던질 수 있게 됐다.** BD-41이 그 결과를 인지하고 *"어떻게 다룰지는 별도 티켓에서 정한다"*로 남겨 둔 건이다.

필터는 `DispatcherServlet` 밖이라 `DataAccessException`이 `@RestControllerAdvice`를 타지 않는다. 401·403용으로 만든 `SecurityErrorWriter`도 예외 처리 경로가 아니라서 타지 않고, 커스텀 `ErrorController`도 없다. 그래서 **클라이언트가 `code`·`message`·`traceId` 계약을 벗어난 응답을 받는다.**

### "DB가 죽으면 어차피 다 죽는다"로 끝나지 않는다

BD-28로 readiness에 `db`가 들어 있어 DB가 끊기면 파드가 트래픽에서 빠진다. 그러나 `periodSeconds: 10` · `failureThreshold: 3`이라 **최대 30초가 걸리고**, 커넥션 풀 고갈처럼 짧게 스치는 순단은 3회 연속 실패에 도달하지 못해 **감지되지 않는다.**

즉 이 결정이 다루는 것은 **readiness가 못 잡는 짧은 순단**이다. 긴 장애는 이미 BD-28이 처리한다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 삼켜서 미인증 취급 → 401 | 코드가 가장 짧다. 필터의 기존 원칙("401을 여기서 쓰지 않는다")과 형태가 같다 | **인프라 실패를 자격증명 실패로 말한다.** 아래 연쇄가 열린다 |
| **(b) 필터에서 503 envelope을 직접 쓴다** | 원인과 표기가 맞는다. `SecurityErrorWriter` 선례를 그대로 쓴다 | 필터가 응답을 쓰는 경로가 하나 늘어난다 |
| (c) 그대로 두고 문서화 | 코드 변경 없음 | 계약 위반이 남는다. 클라이언트가 분기할 수 없는 응답이 계속 나간다 |

### (a)가 여는 연쇄

**클라이언트가 401에 재발급으로 반응하는 것은 관찰이 아니라 계약이다.** 공용 계약이 그렇게 정해 두었다.

> API 호출 → 401
> └ 재발급 호출
> └ 401 → 로그인 화면으로
>
> — [11_인증_설계 §4.4](https://github.com/Team-PinLog/docs/blob/main/static/11_인증_설계.md)

따라서 인프라 실패를 401로 내보내면 **계약을 지키는 클라이언트일수록 반드시 재발급을 시도한다.** 클라이언트 버그를 가정한 시나리오가 아니다.

여기에 Jira 작업에서 넣은 *"재발급이 401이면 인증 쿠키를 지운다"*가 맞물린다.

```
DB 순단 → isActive 던짐 → 삼킴 → 컨텍스트 빔 → 보호 경로 401
  → 클라이언트가 /auth/refresh 호출
  → rotate는 Redis만 쓴다(DB를 타지 않는다) → 204 성공, 새 쿠키 발급
  → 재시도 → 또 401 → 또 재발급 → DB가 회복될 때까지 반복
```

지도 화면처럼 요청을 동시에 보내는 경로에서는 재발급도 동시에 나가고, 그러면 재사용 감지가 터져 **그 회원의 전 세션이 폐기된다**(BD-35). BT-06에서 실제로 밟은 경로다.

같은 §4.4가 이 위험도 이미 적어 두었다 — *"화면 진입 시 API를 여러 개 동시에 쏘면 401이 여러 건 터지는데 … 사용자가 로그아웃됩니다"*. 계약은 그 대응으로 클라이언트에 single-flight를 요구한다. **그러나 그 방어선은 인증 실패를 전제로 설계된 것이다.** 인프라 실패까지 401로 흘려보내면, 계약이 클라이언트에 지운 부담을 서버가 근거 없이 늘리는 셈이 된다.

**즉 (a)는 몇 초짜리 DB 순단을 영구 로그아웃으로 증폭시킬 수 있고, 그 증폭 경로를 공용 계약이 이미 문서화해 두었다.**

## 결정

**(b) — 능동적 선택.** 근거 셋이 같은 방향을 가리켰다.

**① 내부 일관성이 결정적이었다.** 이 시스템은 "DB에 닿지 않는 상태"를 **이미 503이라고 말하고 있다** — BD-28이 넣은 readiness가 그렇고, `ReadinessProbeDatabaseOutageTests`가 그것을 테스트로 고정하고 있다. 같은 원인에 대해 헬스 엔드포인트는 503이라 하고 API는 401이라 하면 시스템이 자기 상태를 두 가지로 말하는 것이 된다. 의존성 장애를 503으로 내보내는 관례도 이미 있다(`ErrorCode.SEARCH_UNAVAILABLE` — AI 검색 연결 실패·타임아웃·5xx).

**② 프레임워크가 같은 문제를 자기 결함으로 보고 있다.** Spring Security의 `AuthenticationServiceException`(백엔드 저장소 불통 시 던지는 예외)에 대해 팀이 *"represents something that went wrong on the server side and shouldn't be handled by `AuthenticationEntryPoint`s"*라고 적고, `AuthenticationException` 상속을 끊거나 별도 예외를 만드는 방향을 논의 중이다([spring-security#12134](https://github.com/spring-projects/spring-security/issues/12134), milestone 7.0.x). (a)를 고르면 그들이 고치려는 상태를 우리가 일부러 만드는 셈이다.

**③ 표준의 503 정의가 이 실패 모드를 직접 지목한다.** *"some server-side applications will reject requests with a 503 status when resource thresholds like memory, CPU, or **connection pool limits** are met"*([MDN 503](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status/503)). 커넥션 풀 고갈이 이 결정이 겨냥한 바로 그 경우다.

필터의 기존 원칙("오류 응답 형식은 한 곳에만")은 지킨다 — `RestAuthenticationEntryPoint`가 쓰는 것과 **같은 `SecurityErrorWriter`**를 쓴다. 새 형식을 만들지 않는다.

### 함께 정한 것 — `rotate`에는 탈퇴 판정을 넣지 않는다

티켓이 함께 물은 질문이다. 지금 `AuthTokenService.rotate`는 탈퇴 여부를 보지 않아 탈퇴한 회원도 재발급에 성공한다.

**넣지 않는다.**

- **얻는 것이 없다.** 탈퇴자가 재발급으로 새 Access를 받아도 그 토큰을 쓰는 순간 이 필터가 막는다(BD-41). 실제 잔여는 Redis 키가 TTL(7일)까지 갱신되는 것뿐이다.
- **잃는 것이 있다.** 재발급은 지금 Redis만 쓴다. DB 확인을 넣으면 **DB 순단이 재발급까지 끌고 간다.** 이 결정이 "DB 순단을 인증 실패로 번지지 않게 한다"고 정해놓고 재발급을 DB에 묶으면 앞뒤가 맞지 않는다. 외부 의존성 하나가 서비스 전체를 끌어내리지 않게 한다는 BD-28·BD-41의 방향과도 어긋난다.

## 결과

- **이 결정으로 감수하는 것**
  - 필터가 응답을 직접 쓰는 경로가 하나 늘었다. 지금까지 이 필터는 "컨텍스트를 채우거나 비운다"만 했다.
  - `DataAccessException`으로 그물을 쳤다. `isActive`가 Spring Data 경유라 JDBC·JPA 예외가 이 계층으로 번역되지만, **다른 종류의 인프라 실패는 여전히 계약을 벗어난다.** 필터가 DB 말고 다른 것을 읽게 되면 같은 판단을 다시 해야 한다.
  - 탈퇴자의 재발급이 계속 성공한다. `logged_in` 쿠키도 갱신되어 UI는 로그인 상태로 보이지만, 모든 API가 401이라 화면은 쓸 수 없다.
  - **공용 계약의 상태 코드 표에 503이 없다.** [08 §1.5](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)의 권장 표는 `200`~`422`뿐이고 5xx가 하나도 없다. 다만 같은 절이 *"`success: false`는 항상 4xx·5xx와 함께 온다"*고 적어 5xx의 존재 자체는 인정하므로 **금지가 아니라 다루지 않은 것**으로 읽었다. `SEARCH_UNAVAILABLE`도 같은 처지다. 표를 채우는 것은 공용 계약 소유자의 몫이라 여기서 고치지 않고 PR에 표시한다.
  - **`Retry-After`를 붙이지 않았다.** MDN과 API 설계 가이드가 503에 권고하지만 이 저장소에 선례가 하나도 없다(`SEARCH_UNAVAILABLE`도 readiness 503도 붙이지 않는다). 이번 건에만 붙이면 503 세 종류 중 하나만 다른 모양이 되므로, 503 전반의 정책으로 별건에서 다룬다.
- **재검토 트리거**
  - 필터가 DB 외의 외부 의존성을 읽게 될 때 — 그물의 범위를 다시 정해야 한다.
  - 503 전반에 `Retry-After` 정책을 세울 때 — 이 경로도 함께 포함한다.
  - 탈퇴자의 재발급 성공이 실제 문제를 만들 때 — 위 "얻는 것이 없다"의 전제가 깨진 것이므로 `rotate` 판단을 다시 한다.
