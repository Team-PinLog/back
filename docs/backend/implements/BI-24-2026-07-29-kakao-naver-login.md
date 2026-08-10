# BI-24. Kakao·Naver 소셜 로그인 추가

- **상태**: ✅ 완료
- **날짜**: 2026-07-29
- **관련**: Jira 작업, [back#33](https://github.com/Team-PinLog/back/issues/33),
  [BI-18](BI-18-2026-07-28-jwt-cookie-session.md)(인증 골격),
  [BT-05](../troubleshooting/BT-05-dotenv-empty-value-overrides-default.md)(작업 중 발견)

## 산출

- `application.yml`에 Kakao·Naver 등록정보와 공급자 엔드포인트 추가.
- `OAuthUserInfo.from`에 두 공급자 정규화 추가. `UnsupportedSocialProviderException` 분기 제거.
- `KakaoNaverLoginCallbackTests` 신설(4개), `SocialLoginRedirectTests`에 진입 2건 추가.
- `StubOAuthProvider`가 공급자별 응답 형태를 내도록 확장, `SocialLoginTestSupport`의 로그인 흐름을 공급자 파라미터화.

## 새로 들인 것은 응답 형태뿐이다

토큰 발급·회전·쿠키·회원 확정은 공급자와 무관한 경로라 [BI-18](BI-18-2026-07-28-jwt-cookie-session.md)이 이미 만들어 뒀다. 이 티켓이 실제로 더한 것은 **공급자마다 다른 사용자 정보 응답을 하나의 형태로 옮기는 일**이다. 셋이 다 다르다.

| 공급자 | 식별자 위치 | 이메일 위치 | `user-name-attribute` |
|---|---|---|---|
| Google | `sub` (최상위) | `email` (최상위) | `sub` |
| Kakao | `id` (최상위, **숫자**) | `kakao_account.email` | `id` |
| Naver | `response.id` | `response.email` | `response` |

## 드러난 것

**① Naver는 Spring의 식별자 검사를 우회한다.** `user-name-attribute: response`는 감싼 **Map**을 지목한다. Spring의 `DefaultOAuth2User`는 그 키의 존재만 확인하므로, `response`가 있고 그 안의 `id`가 없어도 통과한다. Google·Kakao는 최상위 스칼라를 지목해 Spring이 앞단에서 걸러 주는데 **Naver만 그 보증이 없다.** `required(nested(attributes, "response", "id"), "response.id")`가 유일한 방어선이고, 없으면 `provider_user_id` NOT NULL 위반이 되어 원인이 DB까지 내려간다.

**② Kakao는 client secret을 본문으로 받는다.** 기본값(`basic`, HTTP Basic 헤더)이면 토큰 교환이 401이다. `client-authentication-method: client_secret_post`를 명시했다. 게다가 Kakao의 client secret은 **콘솔에서 활성화해야 검사되는 선택 항목**이라, 콘솔 상태와 이 설정이 어긋나면 콜백 마지막 단계에서 실패한다.

**③ 이메일 없는 가입이 실제 기본 경로일 수 있었다.** Kakao는 `account_email` 동의항목을 콘솔에서 설정해야 하고(안 하면 인가 요청 자체가 `KOE205`로 거절된다), 이메일 수집은 비즈 앱 전환을 요구한다. `email`이 `null`인 경로는 [06 §2.2](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md)가 허용하는 상태이고 DB·엔티티·정규화 네 층이 모두 nullable이라 그대로 통과한다. `callbackSucceedsWithoutEmail`로 고정했다 — 선택 동의라 **동의한 사용자도 나중에 철회할 수 있으므로** 이 경로는 비즈 앱 전환 후에도 유효하다.

**④ 등록정보를 추가하자 무관한 테스트 11개가 죽었다.** 원인은 이 티켓이 아니라 `.env.example`이 자격증명을 빈 값으로 정의하고 있던 것이었다 — [BT-05](../troubleshooting/BT-05-dotenv-empty-value-overrides-default.md)에 따로 적었다. Google만 값이 채워져 있어 드러나지 않았던 기존 함정이다.

## 실제 Kakao·Naver로 한 수동 검증

스텁이 구조적으로 덮지 못하는 것을 보려고 실제 계정으로 한 번씩 돌렸다([인증 계약](../../development/authentication.md) §8의 절차).

| 확인 | Kakao | Naver |
|---|---|---|
| `provider_user_id` | `5013244578` — **숫자를 문자열로** 저장 | 43자 불투명 문자열 — `response` 안의 `id` 자체(Map의 `toString()`이 아님) |
| `email` | 비즈 앱 전환 + 동의항목 설정 후 정상 수신 | 정상 수신 |
| 회원 분리 | `member`·`social_account` 각 2건 — `(provider, provider_user_id)`가 달라 별도 회원 | |
| Redis | `auth:refresh:{id}:{jti}` + `auth:refresh-index:{id}` 각 회원마다, 7일 TTL | |
| 쿠키 | `logged_in`·`XSRF-TOKEN`이 `document.cookie`에 보이고 `access_token`·`refresh_token`은 보이지 않음 | |

**①의 방어선이 실제로 값을 했다.** Naver의 `provider_user_id`가 Map 문자열이 아닌 43자 식별자로 저장된 것이 그 증거다.

`auth:refresh-index`는 [BI-21](BI-21-2026-07-29-refresh-reuse-family-revocation.md)이 추가한 것으로, 어제 Google 검증 때는 없었다. Lua `SAVE`가 운영 경로에서 세 명령을 다 실행하고 인덱스에 TTL이 걸리는 것을 실제 Redis에서 확인했다.

## 남은 것

- **`/me/summary`가 이메일을 반환하는데 `null`일 수 있다.** 공용 계약 §1.6이 `null` 필드를 직렬화에서 생략하므로 **응답에 `email` 키 자체가 없다.** 프론트에 폴백 UI가 필요하다는 것을 전달했다. 해당 엔드포인트는 아직 미구현이다.
- **실제 자격증명이 인프라에 없다.** `KAKAO_CLIENT_*`·`NAVER_CLIENT_*`를 Sealed Secret으로 주입해야 하며, **빈 값으로 주입하면 기동이 실패한다**(BT-05).
- **`08_API_명세` §3.1은 이미 세 공급자를 적고 있다.** 이번 변경은 명세를 고치는 것이 아니라 **구현이 명세를 따라잡은 것**이라 공용 문서 변경이 없다.

## 검증

`./gradlew clean check --no-daemon` — **326개 통과, 실패 0**(dev 리베이스 후 기준. 이 티켓이 더한 것은 6개다).
