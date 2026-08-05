# BI-41. Google 탈퇴 시 refresh token을 폐기해 승인까지 해제

- **상태**: ✅ 완료
- **날짜**: 2026-08-05
- **관련**: S15P11A705-309, [back#190](https://github.com/Team-PinLog/back/issues/190),
  [BD-48](../decisions/BD-48-unlink-before-withdrawal.md)(§① 정정),
  [BI-38](BI-38-2026-08-04-unlink-before-withdrawal.md)(탈퇴 왕복 구현)

## 증상과 원인

Google로 가입한 회원이 탈퇴해도 계정의 「서드파티 앱 및 서비스」에 앱이 남았다. 서버는 access token을 `/revoke`에 보내 `200`을 받고 로그에 `unlinked`를 남긴다 — **그 `200`은 토큰이 폐기된 것이지 승인이 해제된 것이 아니다.**

| 공급자 | 해제 단위 | 승인을 지우는 토큰 |
|---|---|---|
| Kakao `/v1/user/unlink` | 연결 | access token |
| Naver `/oauth2.0/revoke` | 연결 | access token |
| **Google `/revoke`** | **토큰** | **refresh token** |

폐기의 연쇄는 access → refresh 방향이고 승인은 refresh token 쪽에 달려 있다. 우리 인가 요청은 `scope: openid,email`뿐이라 `access_type=offline`이 없었고, refresh token 자체를 받지 않았으므로 연쇄할 대상이 없었다.

## 산출

- **`WithdrawalAwareAuthorizationRequestResolver`** — 탈퇴 왕복이고 `registration_id`가 `google`일 때만 `additionalParameters`에 `access_type=offline`·`prompt=consent`를 넣는다. registrationId는 위임이 `attributes`에 넣어 둔 값을 읽는다(경로에서 다시 자르지 않는다 — 매칭 규칙이 바뀌면 조용히 어긋난다).
- **`ProviderTokens`**(신설) — `accessToken` + nullable `refreshToken`. 두 값을 묶은 이유는 **공급자마다 승인을 지우는 토큰이 다르기 때문**이다.
- **`OAuthLoginSuccessHandler.providerTokensOf`** — `OAuth2AuthorizedClient`에서 refresh token까지 꺼낸다. 없을 수 있으므로 판단은 클라이언트에 맡긴다.
- **`GoogleUnlinkClient`** — refresh token이 있으면 그것을, 없으면 access token을 `token`에 싣는다.
- `SocialUnlinkClient.unlink(String)` → `unlink(ProviderTokens)`. Kakao·Naver 구현은 `tokens.accessToken()`만 쓴다.

## 설계 판단

### 로그인 진입에는 붙이지 않는다

`prompt=consent`를 로그인에 붙이면 **매 로그인마다 동의 화면**이 뜬다. 탈퇴는 계정 삭제 앞이라 명시적 승인이 오히려 맞고, Google이 refresh token을 **첫 인가에만** 주므로 탈퇴 시점에 확실히 받으려면 이 값이 필요하다.

### Kakao·Naver는 건드리지 않는다

연결 단위 API라 access token으로 이미 승인이 사라진다. Google 전용 파라미터를 남의 인가 요청에 실으면 공급자가 거절할 수 있다.

### 보관하지 않는다는 원칙은 유지된다

refresh token은 탈퇴 왕복의 **토큰 교환 응답**으로 와서 같은 요청 안에서 `/revoke`로 나가고 사라진다 — 지금 access token이 지나가는 경로와 같고 DB·Redis에 남지 않는다. BD-48 §①이 기각한 것은 **로그인 시점에 받아 보관**하는 안이며, 그쪽은 암호화·보관 상한·유출 위험이 따라붙는다.

## 검증

`./gradlew clean check --no-daemon` 통과.

| 테스트 | 고정한 것 |
|---|---|
| `googleWithdrawalAsksForARefreshToken` | 탈퇴 왕복에 `access_type=offline`·`prompt=consent`가 실린다 |
| `googleLoginIsNotAskedForARefreshToken` | **로그인 진입에는 실리지 않는다** |
| `otherProvidersAreUntouched` | Kakao 탈퇴 왕복에는 실리지 않는다 |
| `revokesTheRefreshTokenWhenPresent` | refresh token이 있으면 그것을 `token`에 싣는다 |
| `revokesWithFormEncodedToken` | 없으면 access token으로 떨어진다 |

**로컬 실물 확인** — 프론트(`dev`) + 백엔드를 띄우고 vite 프록시를 통해 실제 경로로 호출했다.

```
로그인 진입      → access_type·prompt 없음
탈퇴 왕복 진입    → access_type=offline · prompt=consent · code_challenge_method=S256
```

그리고 **브라우저로 Google 재가입 후 탈퇴해 계정의 서드파티 목록에서 사라지는 것을 확인했다.** 이 확인이 이 티켓의 완료 조건이었다 — 로그의 `unlinked`만으로는 판정할 수 없기 때문이다.

## 남긴 것

**탈퇴 티켓이 재사용된다.** 탈퇴 완료 뒤 같은 티켓으로 왕복을 다시 시작하면 공급자에서 새 승인이 생기고, 우리는 그것을 끊지 않는다(BD-48 §⑤ — 마스킹 후엔 소유 확인 불가). BD-48 §②가 *"일회 소비한다"*고 적어 놓고 구현되지 않은 상태다. 이번 증상의 원인은 아니며 별도 티켓으로 다룬다.
