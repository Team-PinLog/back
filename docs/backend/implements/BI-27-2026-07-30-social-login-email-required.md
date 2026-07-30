# BI-27. 소셜 로그인 이메일 필수화

- **상태**: ✅ 완료
- **날짜**: 2026-07-30
- **관련**: S15P11A705-152, [back#97](https://github.com/Team-PinLog/back/issues/97),
  [docs#28](https://github.com/Team-PinLog/docs/pull/28)(공용 계약 개정 — 선행),
  [BI-24](BI-24-2026-07-29-kakao-naver-login.md)(이메일 `null` 허용을 계약으로 고정했던 기록)

## 산출

- `V6__social_account_email_not_null.sql` — `core.social_account.email`에 `NOT NULL`.
- `OAuthUserInfo`의 세 공급자 분기 모두 이메일을 `required(...)`로 끊는다. record 컴포넌트에서 `@Nullable` 제거.
- `SocialAccount` — `@Column(nullable = false)`, 팩토리 파라미터에서 `@Nullable` 제거.
- 이메일 `null`을 계약으로 고정하던 테스트를 반대 방향으로 뒤집고, 스키마 제약을 지키는 단언을 추가했다.

## 계약이 먼저 바뀌어야 했다

`06 §2.2`가 *"미동의·미제공 시 `null`일 수 있다"*로 정하고 있었다. 구현만 바꾸면 공용 계약을 위반하므로([`CLAUDE.md`](../../../CLAUDE.md) 9번) [docs#28](https://github.com/Team-PinLog/docs/pull/28)을 먼저 올려 병합했다. 그 PR에서 함께 정리한 것이 이 구현의 전제다.

- **1차 보장은 공급자 콘솔의 필수 동의 설정이다.** 사용자가 이메일만 거절하고 진행하는 선택지가 동의 화면에 없다. 따라서 이 구현이 막는 경로는 **사용자가 일상적으로 밟는 흐름이 아니라 방어선**이다.
- **마스킹은 치환이며 `NULL`이 아니다.** 이걸 계약에 적지 않으면 탈퇴 구현이 `NULL`을 넣어 이 제약과 부딪힌다. 같은 테이블의 `provider_user_id`가 이미 `NOT NULL`이면서 마스킹 대상이라 전제는 원래 있었고, 글로 옮긴 것이다.

## 드러난 것

**① 뒤집을 테스트가 예상보다 많았다.** 착수 전에 찾아 둔 것은 `OAuthUserInfoTest` 2건과 `KakaoNaverLoginCallbackTests` 1건이었는데, `clean check`에서 `SocialAccountPersistenceTests`가 걸렸다. `emailIsOptional`이 *"이메일 없이도 저장되고 컬럼에 null로 남는다"*를 영속성 층에서 고정하고 있었다.

같은 파일의 **무관해 보이던 테스트도 함께 깨졌다** — `findsActiveAccountByProviderAndProviderUserId`가 조회 대상을 만들면서 이메일을 `null`로 넣고 있었다. 계약을 검증하는 테스트가 아니라 **준비 코드에 `null`이 섞여 있던 것**이라, 사전 조사(`useEmail(null)`·`isNull()` 검색)로는 걸리지 않았다.

**② 두 방어선이 실제로 독립이라는 것을 뮤테이션 검사가 보여 줬다.** 정규화의 `required(...)`를 되돌려 DB `NOT NULL`만 남긴 상태에서:

| 테스트 | 결과 |
|---|---|
| `OAuthUserInfoTest` 이메일 4건 | **실패** — 예외를 단언하므로 |
| 콜백 테스트 3건(Google·Kakao·Naver) | **통과** |

콜백 테스트가 통과한 이유는 DB 제약이 대신 잡아 **같은 `OAUTH_FAILED`로 귀결**했기 때문이다. 즉 HTTP 경계에서는 어느 층이 막았는지 구별되지 않는다. 이건 결함이 아니라 층이 갈린 결과다 — **콜백 테스트는 관측 가능한 계약을, 단위 테스트는 어느 층이 막는가를** 고정한다. 반대 방향(마이그레이션의 `ALTER`를 주석 처리)에서는 `FlywayMigrationTests.socialAccountEmailIsNotNull`이 잡는다.

**③ 백필을 넣지 않았다.** 운영 DB에 이 컬럼이 `NULL`인 행이 없다. 있었다면 **채울 값이 없다** — 이메일은 공급자가 주는 값이고 서버가 만들어 낼 수 있는 것이 아니다. 임의 값을 넣으면 "표시할 이메일"이라는 목적 자체가 깨진다. 그런 행이 있는 환경에서는 마이그레이션이 실패하는 편이 맞다고 보고 그 판단을 SQL 주석에 남겼다.

## 감수하는 것

**사용자가 공급자 쪽에서 동의를 철회하면 이후 재로그인이 실패한다.** 이미 저장된 값은 남아 있어 기존 세션과 화면 표시는 영향받지 않는다. 콘솔이 필수 동의라 재동의를 요구할 것으로 보지만 실측하지 않았다.

**실패 사유가 `OAUTH_FAILED`로 뭉개진다.** 프론트에서 "이메일 동의가 필요합니다"를 안내할 수 없다. 사유별로 `error` 값을 가르지 않는 것은 기존 결정이고([`OAuthLoginFailureHandler`](../../../src/main/java/com/pinlog/pinlogback/global/security/oauth/OAuthLoginFailureHandler.java)), 이 실패는 사용자가 우리 화면에서 고칠 수 있는 것이 아니라 유지했다. 프론트에 확인해 분기 추가가 필요하지 않다는 답을 받았고 `08 §3.2`에 명시했다.

## 남은 것

- **탈퇴 구현([S15P11A705-65](https://ssafy.atlassian.net/browse/S15P11A705-65))이 마스킹 치환값을 정한다.** 계약이 정한 것은 `NULL`이 아니라는 것과 원본을 되돌릴 수 없어야 한다는 것까지다. 치환값이 회원끼리 겹쳐도 무해하다(유니크가 활성행만 대상이고 마스킹 시점엔 `deleted_at`이 채워져 인덱스 밖이다).
- **번호가 `BI-25`에서 `BI-27`로 두 칸 밀렸다.** 세 PR이 `BI-25`를 동시에 선점했고 머지 순서대로 확정됐다 — [#98](https://github.com/Team-PinLog/back/pull/98)이 `BI-25`, [#100](https://github.com/Team-PinLog/back/pull/100)이 `BI-26`, 이 기록이 `BI-27`이다. `#100`이 머지되기 전에 `BI-27`을 미리 배정해 재번호를 한 번만 했다. 규약대로 "미머지 브랜치의 파일명 선점은 예약이 아니다"가 실제로 세 번 적용된 사례다.

## 검증

`./gradlew clean check --no-daemon` — **342개 통과, 실패 0.**

뮤테이션 검사 2종 — 마이그레이션의 `ALTER` 주석 처리 시 `socialAccountEmailIsNotNull` 실패, 정규화의 `required(...)` 되돌림 시 `OAuthUserInfoTest` 이메일 4건 실패. 각각 확인 후 원복했다.
