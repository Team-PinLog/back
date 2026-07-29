# BI-21. Refresh 재사용 시 회원 단위 세션 폐기

- **상태**: ✅ 완료
- **날짜**: 2026-07-29
- **관련**: S15P11A705-131, [back#78](https://github.com/Team-PinLog/back/issues/78),
  [BD-35](../decisions/BD-35-refresh-reuse-family-revocation.md)(이 구현의 결정),
  [BD-32](../decisions/BD-32-refresh-reuse-no-family-revocation.md)(대체된 결정),
  [BI-18](BI-18-2026-07-28-jwt-cookie-session.md)(세션 JWT 도입)

## 산출

- `RefreshTokenStore`에 회원별 인덱스(`auth:refresh-index:<memberId>` Set)와 `revokeAll(memberId)`를 추가한다.
- `save`가 `jti`를 인덱스에 넣고 인덱스 TTL을 Refresh 수명으로 늘린다. `consume`은 소비한 `jti`를 인덱스에서 뺀다.
- `AuthTokenService.rotate`가 재사용을 감지하면 `revokeAll`을 호출한 뒤 401을 던진다.
- `AuthTokenContractTests`에 테스트 2개 추가, 기존 회전 테스트 1개 재구성.

## RED — 무엇이 실패했는가

완료 조건 1번을 그대로 테스트로 옮겼다. 같은 공급자 계정으로 두 번 로그인해 한 회원의 두 세션(`deviceA`·`deviceB`)을 만들고, `deviceA`를 회전한 뒤 **회전 전 `deviceA`를 다시** 보낸다.

```
AssertionFailedError: [재사용이 감지됐으면 다른 기기의 Refresh도 끊겨야 한다]
expected: 401
 but was: 204
```

`deviceB`가 204로 살아 있었다 — BD-32가 감수 사항으로 적어 둔 바로 그 구멍이다. 회전으로 갓 발급된 `rotatedA`(유출 시나리오에서 **공격자가 들고 있을 토큰**)도 유효했다.

## 구현하며 드러난 것

**① 기존 테스트가 반대 사실을 고정하고 있었다.** `refreshRotatesAndInvalidatesPreviousToken`이 재사용 401을 확인한 **뒤에** "회전 후 토큰은 계속 유효하다"를 단언했다. 계열 폐기를 넣으면 그 단언이 깨진다 — 재사용 감지가 이미 그 토큰을 폐기했기 때문이다.

단언을 지우지 않고 **순서를 뒤집었다.** 회전 사슬이 이어지는지를 재사용 검사보다 앞에서 확인한다. 두 계약(회전 사슬 / 재사용 후 폐기)이 한 단언에 섞이면, 나중에 하나가 깨졌을 때 어느 계약이 깨진 것인지 알 수 없다.

**② 폐기 범위에 "갓 발급된 토큰"을 넣는 것이 핵심이다.** 재사용된 `jti`는 이미 소비돼 없으므로, 폐기 대상은 **남아 있는 것들**이다. 그중 하나가 방금 회전으로 나온 토큰인데, 유출 시나리오에서는 그것이 공격자가 받은 토큰이다. 이걸 빼면 폐기가 아무 의미가 없다 — 테스트에 별도 단언으로 못 박았다.

**③ 과하게 폐기하는 실수를 잡을 테스트가 필요했다.** 계열 폐기가 재사용 감지 밖으로 새면 세션 독립성([BD-21](../decisions/BD-21-auth-token-model.md)이 `jti`마다 키를 하나 둔 이유)이 조용히 깨진다. `normalRotationKeepsOtherSessionsAlive`가 정상 회전에서 다른 기기가 살아 있음을 고정한다. 이 테스트는 구현 전에도 통과했고 구현 후에도 통과해야 한다 — 회귀 방향이 반대인 단언이다.

**④ 인덱스 TTL을 발급마다 갱신해야 한다.** `SADD`만 하면 Set에 TTL이 없어 **영구 키**가 된다. Redis에 만료되지 않는 키가 회원 수만큼 쌓인다. `save`마다 `EXPIRE`를 다시 걸어 "가장 나중에 발급된 토큰만큼 살아 있는" 수명으로 맞췄다.

**⑤ 테스트 전제를 단언으로 만들었다.** "같은 subject로 두 번 로그인하면 같은 회원"이 깨지면, 이 테스트는 *다른 회원의 세션까지 끊는다*는 정반대 사실을 통과시킨다. Access 토큰의 `sub`를 비교해 전제를 명시했다.

## 남은 것

- **회원 탈퇴([S15P11A705-65](https://ssafy.atlassian.net/browse/S15P11A705-65))가 같은 `revokeAll`을 쓴다.** BD-32가 "두 작업을 같이 하는 것이 자연스럽다"고 적어 둔 이유이고, 이번에 그 연산이 준비됐다.
- **오탐 관측 경로가 없다.** 재사용 감지 시 WARN은 남지만, 그것이 유출인지 클라이언트 버그인지 구별할 정보는 로그에 없다. 오탐이 문의로 올라오면 [BD-35](../decisions/BD-35-refresh-reuse-family-revocation.md)의 재검토 트리거를 따른다.
- 공용 계약([08 §3.3](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md))에 이 동작을 적을지는 별도 판단으로 남겼다. 명세는 "재사용: 401"까지만 정의하고 거짓을 말하지 않으며, 클라이언트가 취할 행동이 달라지지 않는다.

## 검증

`./gradlew clean check --no-daemon` — **229개 통과, 실패 0.**
