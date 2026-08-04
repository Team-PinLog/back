# BI-38. 탈퇴 시 공급자 연결 해제를 선행하고 성공했을 때만 소프트 삭제

- **상태**: ✅ 완료
- **날짜**: 2026-08-04
- **관련**: S15P11A705-285, [back#176](https://github.com/Team-PinLog/back/issues/176),
  [BD-48](../decisions/BD-48-unlink-before-withdrawal.md)(순서·수단·어휘 결정),
  [BD-41](../decisions/BD-41-withdrawn-member-check-in-authentication-filter.md)(Refresh 폐기를 커밋 이후로 둔 반대 방향),
  공용 계약 08 §3.6 · 06 §6.9

## 산출

흐름이 한 요청에서 왕복 하나로 바뀌었다.

```text
DELETE /v1/me                        → 200 + authorizationUrl (아무것도 지우지 않는다)
GET  /v1/auth/authorize/{id}?ticket= → 티켓 검증, attributes에 memberId, 공급자로 302
GET  /v1/auth/{id}/callback          → 로그인/탈퇴 분기
                                     → 계정 일치 확인 → 해제(재시도) → 소프트 삭제 → 쿠키 만료
```

| 신설 | 역할 |
|---|---|
| `JwtTokenProvider.issueWithdrawalTicket` / `parseWithdrawalTicket` | `token_use=withdrawal` 5분 티켓 |
| `WithdrawalAuthorizationService` | 공급자 판정 + 진입 URL 조립 |
| `WithdrawalAwareAuthorizationRequestResolver` | 티켓 검증 → `attributes`에 대상 회원 |
| `RequestScopedOAuth2AuthorizedClientRepository` | 공급자 토큰을 요청 범위로만 보관 |
| `SocialUnlinkClient` + Kakao·Google·Naver 구현 | 해제 호출 |
| `WithdrawalCompletionService` | 일치 확인 → 해제 → 삭제 순서 |
| `ClientRedirectCodes` | 복귀 어휘 한자리 |

`MeController.withdraw`가 `204` → `200 + WithdrawalStartResponse`. 경로·메서드는 그대로다.

## 설계 판단

### 진입을 서명 티켓으로 막는다 — CSRF가 GET 뒤로 새기 때문

인가 진입은 브라우저 내비게이션이라 `GET`이다. 의도를 쿼리 파라미터로만 받으면 악성 사이트가 피해자를 그 경로로 유도해 계정 삭제까지 이르게 할 수 있고, **`DELETE /v1/me`에 CSRF를 걸어도 뒤 단계가 GET이라 우회된다.** `SameSite=Strict` 쿠키는 배제했다 — 공급자에서 돌아오는 콜백도 크로스 사이트 내비게이션이라 그때 쿠키가 실리지 않는다.

Redis 저장은 (C)안으로 기각했고(BT-06의 영속성 부재), 그래서 **서버가 티켓을 무효화하지는 않는다.** 남는 성질과 감수 근거는 BD-48 §②에 적었다.

### 컨텍스트를 `state`가 아니라 `attributes`에 싣는다

콜백 경로를 나누면 `application.yml`과 공급자 콘솔 **양쪽의 `redirect-uri`**를 바꿔야 한다. `attributes`는 인가 요청 객체째로 우리 쿠키에 보관돼 클라이언트에 나가지 않으므로, `state` 문자열에 인코딩할 때 생기는 tampering·swapping 문제(RFC 9700)를 피한다. `memberId`도 같은 자리다 — 쿠키로 다시 식별하면 왕복 중 Access(30분) 만료 시 탈퇴를 완료할 수 없다.

### 공급자 토큰 저장소를 요청 범위로 바꿨다

해제에 쓸 토큰이 `OAuth2AuthenticationToken`에 실리지 않아 저장소에서 꺼내야 하는데, Spring 기본값은 `HttpSession`을 만들어 `SessionCreationPolicy.STATELESS` 선언과 어긋난다. 인가 요청 저장소를 쿠키로 바꾼 것과 같은 이유다. 소비자가 성공 핸들러 하나뿐임을 확인하고 바꿨다 — 오래 들고 있을수록 보관해야 할 자격증명이 늘 뿐이라 BD-48이 토큰을 저장하지 않기로 한 결정과도 어긋난다.

### 트랜잭션으로 묶지 않았다 — 남는 창이 한 방향뿐이라서

해제는 외부 HTTP다. 트랜잭션 안에 두면 공급자 응답을 기다리며 DB 커넥션을 잡는다. 남는 창은 **해제 성공 + 삭제 실패** 하나이고, 그 방향은 무해하다 — 사용자가 다시 시도하면 되고 공급자는 이미 폐기된 토큰에도 성공을 준다. 반대 방향은 복구가 없다.

### 재시도를 같은 단위에 넣었다

214를 뒤집는 근거가 "실제 거절은 드물어진다"였다. 흡수 장치가 없으면 503 한 번이 탈퇴를 영구히 막아 그 근거가 빈다. 일시성 판정을 예외가 나르게 했다 — 5xx·429·응답 없음은 되풀이, 나머지 4xx는 즉시 포기. 사용자가 리다이렉트 뒤에서 기다리는 구간이라 3회·0.2초·0.4초로 짧다.

### 되돌릴 수 없는 경로 둘을 닫았다

- **소셜 계정 둘 이상** — 한 왕복은 한 공급자만 인가하는데 마스킹은 전부에 걸린다. 시작·완료 양쪽에서 거절한다. 지금은 도달 불가지만 계정 연결 기능이 붙는 순간 조용히 깨진다.
- **이미 탈퇴한 회원의 두 번째 왕복** — 완료로 본다. 그 왕복이 만든 새 인가는 끊지 않는다. 마스킹으로 소유를 확인할 수 없고, 확인 없이 끊으면 §⑤가 막으려는 경로가 열린다.

## 검증

`./gradlew clean check --no-daemon` 통과.

### 테스트

| 테스트 | 고정한 것 |
|---|---|
| `MemberWithdrawalApiTests` | 시작이 아무것도 지우지 않는 것 + 완료 후 연쇄 삭제·마스킹 12건 |
| `WithdrawalCompletionServiceTest` | 해제 → 삭제 순서, 실패 시 무변경, 계정 불일치, 재시도 흡수·즉시 포기, 계정 다중, 두 번째 왕복 |
| `WithdrawalAwareAuthorizationRequestResolverTest` | 티켓 검증, **티켓이 인가 URL에 실리지 않는 것**, **PKCE 생존** |
| `ProviderTokenReachesSuccessHandlerTest` | 공급자 토큰이 성공 핸들러에 닿는 순서 |
| `OAuthCallbackWithdrawalBranchTest` | 분기와 복귀 어휘 |
| `OAuthCallbackCancellationTest` | 필터 체인을 태워 `access_denied` → `WITHDRAWAL_CANCELLED` |
| `SocialUnlinkClientTest` | 3사 요청 형태, 일시/영구 실패 판정 |

**뒤 둘은 "코드를 읽어 추론한 전제"를 고정하려고 뒤늦게 추가했다.** 성공 핸들러를 단위로 부르며 토큰을 손으로 넣어 두면, 실제로 저장되지 않아도 통과한다. PKCE도 잃으면 인가는 성공하고 토큰 교환만 죽어 증상이 콜백 실패 하나로만 보인다.

### 로컬 실물 스모크 (curl)

앱을 고정 키로 띄우고 19개 경로를 실제로 호출했다. 공급자 토큰·userinfo 엔드포인트는 로컬 스텁으로 돌려 인가 성공 이후 구간까지 태웠다.

| 확인 | 결과 |
|---|---|
| 미인증 / CSRF 없음 | `401` / `403` |
| 인증된 탈퇴 시작 | `200` + `authorizationUrl`, 인증 쿠키 유지, DB 무변경 |
| 티켓 클레임 | `token_use=withdrawal`, 수명 300초 |
| Access·Refresh·만료·타 키 서명을 티켓 자리에 | 모두 `WITHDRAWAL_FAILED` |
| 정상 티켓 진입 | `302` 공급자, `code_challenge_method=S256`, **티켓 미포함** |
| `access_denied` (탈퇴 / 로그인) | `WITHDRAWAL_CANCELLED` / `OAUTH_FAILED` |
| 실제 Google 토큰 교환 실패 | `invalid_grant` → `WITHDRAWAL_FAILED`, 회원 무변경 |
| 스텁 인가 성공 → 일치 → 해제 호출 | 실제 Google revoke 호출, `400` → 재시도 0회 → 회원 무변경 |
| 스텁이 다른 계정 | `WITHDRAWAL_ACCOUNT_MISMATCH`, **해제 미호출** |
| 소셜 계정 둘 / 없음 | `500` / `401` |

스텁 로그에 `code_verifier=…`가 찍혀 **PKCE가 실제 토큰 교환에 실려 간 것**이 확인됐다.

## 남긴 것

**해제가 200을 받는 성공 완주와 재시도 실동작은 로컬에서 실행하지 못했다.** 해제 URL이 코드 상수라 스텁으로 돌릴 수 없어, 로컬 토큰으로는 공급자가 항상 실패를 준다. 단위 테스트가 `MockRestServiceServer`로 덮고 있으며, 실물 확인은 스테이징에서 3사 각각 한 번씩 필요하다. 테스트만을 위해 해제 URL을 설정 키로 빼지 않았다.

**진짜 동시에 도착한 두 콜백**은 둘 다 해제(멱등)하고 하나만 삭제에 성공한다. 진 쪽은 `WITHDRAWAL_FAILED`를 받지만 실제로는 탈퇴됐다 — 데이터는 안전하고 문구만 어긋난다. 확률이 낮아 잠금을 걸지 않았다.

**프론트가 아직 이 계약을 모른다.** `deleteAccount()`가 응답 본문을 버리고 `authorizationUrl`이 없다. 배포 순서가 어긋나면 "탈퇴가 완료되었습니다"가 뜬 채 아무것도 지워지지 않는다.
