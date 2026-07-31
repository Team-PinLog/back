# BI-31. 소셜 로그인 진단 로그 강화

- **상태**: ✅ 완료
- **날짜**: 2026-07-31
- **관련**: S15P11A705-186, [back#134](https://github.com/Team-PinLog/back/issues/134),
  후속 [S15P11A705-187](https://ssafy.atlassian.net/browse/S15P11A705-187)(이 로그로 하위 원인을 특정한다)

## 산출

로그 세 줄이다. 소스 변경은 `+34 −2`이고 그중 비로그 변경은 지역 변수 추출 한 줄이다.

| 위치 | 로그 |
|---|---|
| `SocialLoginService.signUp` | `INFO member signed up: memberId=…, provider=…` |
| `OAuthLoginSuccessHandler` | `INFO social login succeeded: memberId=…, provider=…` |
| `OAuthLoginFailureHandler` | `WARN social login failed: [예외타입] 메시지` + 스택·cause |

## 왜 필요했는가 — 조사가 실제로 막혔다

운영의 간헐적 `OAUTH_FAILED`를 Loki로 조사해 **분류까지는 갔다.**

```
2026-07-31T01:41:56.317Z  WARN [31c41097-…] OAuthLoginFailureHandler
  : social login failed: [authorization_request_not_found]
```

인가 요청 쿠키를 찾지 못한 것이고, 그 쿠키는 단일 슬롯이며 콜백에서 즉시 만료되므로 하위 원인은 둘이다 — **중복 콜백**이거나 **로그인을 두 번 시작**한 것이다.

**둘 다 "다른 요청이 성공했는가"를 봐야 갈리는데 그 기록이 없었다.** 성공 경로가 로그를 남기지 않았기 때문이다(`OAuthLoginSuccessHandler`의 유일한 로그가 동시 가입 재시도 한 줄, `AuthTokenService.issue`는 무기록). traceId로 훑어도 실패 라인 하나뿐이었다.

> `created concurrently` 쿼리로 확인하려 했으나 그 로그는 **첫 가입 경합에서만** 찍힌다. 기존 회원이 경합하면 둘 다 계정을 찾기만 해 INSERT 충돌이 없다 — 운영 사용자는 이미 계정이 있으므로 그 쿼리는 애초에 답을 줄 수 없었다.

## 목적이 테스트로 증명된다

**운영에서 본 실패를 재현했다.** 같은 `state`로 콜백을 두 번 부르면 첫 번째가 쿠키를 소비하고 두 번째가 찾지 못한다.

```
social login succeeded: memberId=N, provider=GOOGLE
social login failed: [OAuth2AuthenticationException] [authorization_request_not_found]
```

`duplicateCallbackLeavesSuccessAndFailurePaired`가 이 두 줄이 **함께 남는 것**을 고정한다. `-187`이 막혔던 지점이 이것이라, 이 테스트가 곧 이 티켓의 완료 근거다.

## 규약 판단 — `INFO`를 택한 근거

`logging.md`의 레벨 표가 **"`INFO`는 요청마다 남기지 않습니다"**로 정한다. 그래도 `INFO`인 이유가 넷이다.

- 로그인은 요청마다가 아니라 **세션당 1회**다
- 가입은 그 표가 든 **"주요 상태 변화"**에 정확히 해당한다
- **`DEBUG`는 운영 기본 레벨에서 출력되지 않아** 조사 목적에 쓸 수 없다
- 실패가 `WARN`이라 **짝지어 보려면 같은 레벨대**에 있어야 한다

가입과 세션 발급을 각자의 자리에서 한 번씩 남긴다 — 같은 사건을 여러 계층에서 중복 로깅하지 않는다는 규약을 따른 것이고, 그래서 `SocialLoginService.login`의 반환 타입을 건드리지 않았다.

## 걷어낸 것 — 단계 라벨

처음에는 실패 로그에 `stage=normalize`처럼 단계를 실었다. `issueSession`이 별도 메서드라 값 전달로는 갱신이 전달되지 않아 **`Stage`라는 상자 클래스**를 만들었다. 리뷰에서 그 클래스의 존재 이유를 묻는 지적을 받고 다시 보니 셋이 나왔다.

**① 상자는 설계상 필요가 아니라 메서드 경계 우회였다.** 필드 하나만 든 클래스이고, 지역 변수로 두려면 메서드를 인라인해야 했다.

**② 라벨 하나는 도달 불가였다.** `sendRedirect`는 `IOException`을 던지고 우리 `catch`는 `RuntimeException`만 잡는다. 그래서 `stage=redirect`는 **절대 찍히지 않는다.** 이 사실은 *"`static final String`으로 하면 안 되나"*를 확인하는 과정에서 드러났다 — 질문이 없었으면 죽은 라벨을 그대로 커밋했을 것이다.

**③ 정보가 중복이었다.** 예외 타입과 메시지가 이미 단계를 말한다.

| 실패 | 라벨 없이 보이는 것 |
|---|---|
| 정규화 | `[InternalAuthenticationServiceException] 공급자 응답에 필수 속성이 없다: response.email` |
| 가입 경합 | `[DataIntegrityViolationException] … ux_social_account_provider_user` |
| 토큰 발급 | `[RedisConnectionFailureException] …` |

그래서 라벨을 빼고 테스트를 **예외 타입·메시지 기준**으로 바꿨다(`internalFailureIsAttributableToItsStage`). 이슈의 완료 조건 *"로그만 보고 실패 단계를 구별할 수 있다"*는 수단이 바뀐 채 그대로 성립한다.

> 참고로 제안받은 `private static final String stage`는 두 이유로 성립하지 않는다 — `final`이라 재대입이 막히고, `static`이면 싱글턴 빈에서 **요청 간에 공유돼** 동시 로그인이 서로의 단계를 덮어쓴다. 26ms 차 동시 콜백이 실측된 저장소라 가정적 위험이 아니다.

## 개인정보를 남기지 않는다

`memberId`와 `provider`만 남긴다. `logsDoNotContainPersonalData`가 포착된 모든 라인에 **`@`와 공급자 식별자가 없음**을 단언한다 — 이메일은 개인정보고 `provider_user_id`는 공급자 식별자다.

실패 경로도 공급자 응답 본문을 찍지 않는다. 예외 메시지에 담긴 것은 우리가 만든 문장(`공급자 응답에 필수 속성이 없다: response.email`)이고 값이 아니라 **경로 이름**이다.

## 감수하는 것

- **성공 로그가 트래픽에 비례해 늘어난다.** 세션당 1회라 요청 로그보다는 훨씬 적지만 0은 아니다. Loki 보존·용량에 영향이 있으면 레벨을 내리는 것이 첫 조정 지점이다.
- **`stage=redirect` 구간의 실패는 여전히 이 로그에 안 남는다.** `IOException`이 `catch (RuntimeException)` 밖이라 메서드 밖으로 나간다. 클라이언트 연결이 끊긴 경우가 대부분이라 조사 가치가 낮다고 보고 그대로 뒀다.

## 검증

`./gradlew clean check --no-daemon` — **425개 통과, 실패 0, 오류 0.**

신규 6건은 `SocialLoginTestSupport`로 실제 콜백을 돌고 `ListAppender`로 로그를 포착한다(`TraceIdFilterTest`와 같은 방식).

**뮤테이션 4종** — 네 지점이 각각 독립임을 확인했다.

| 되돌린 것 | 실패 |
|---|---|
| 성공 로그 | **3건** — 성공·가입·중복콜백 세 테스트가 모두 여기에 의존한다 |
| 실패 로그의 예외 타입 | 1건 |
| 가입 로그 | 1건 |
| (걷어낸) 단계 라벨 | 1건 — 형태를 바꿔 남았다 |

## 남은 것

**`-187`이 이 로그로 진행된다.** 운영에서 실패 라인을 찾아 **같은 시간대의 성공 라인이 있는지** 보면 하위 원인이 갈린다 — 있으면 중복 콜백, 없으면 로그인 두 번 시작이다. 배포 후 조사할 수 있다.

조사 시 시간대를 혼동하지 않는다. 컨테이너에 `TZ`가 없어 앱은 **UTC**(`…Z`)로 찍고 로컬 개발은 `+09:00`이며, Grafana의 `timestamp`는 브라우저 시간대로 렌더된다. `logging.md`에 이 사실이 적혀 있지 않다 — 별건으로 남길 가치가 있다.
