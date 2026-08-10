# 동시 회전에서 새던 전 세션 폐기를 막고, 재발급 실패가 쿠키를 정리하게 했다

- **날짜**: 2026-08-03
- **추적**: Jira 작업
- **관련**: [BT-06](../troubleshooting/BT-06-refresh-revocation-leak-under-concurrent-rotation.md) · [BD-35](../decisions/BD-35-refresh-reuse-family-revocation.md) · [back#165](https://github.com/Team-PinLog/back/issues/165)

프론트가 "`logged_in` 쿠키 수명과 Refresh TTL이 어긋난 것 같다"고 제보했는데 그건 아니었다. 세 값이 `refresh-token-ttl: 7d` 한 곳에서 나오므로 어긋날 수가 없다. 증상·원인 추적은 [BT-06](../troubleshooting/BT-06-refresh-revocation-leak-under-concurrent-rotation.md)에 남겼고, 여기에는 고치면서 내린 판단만 적는다.

## 결정을 지키는 수정이지 바꾸는 수정이 아니다

동시 회전으로 재사용이 오탐되는 것 자체는 BD-35가 감수하기로 한 대가다. "동시 회전에 짧은 유예를 두고 같은 새 토큰을 돌려준다"는 완화책이 흔하고 이번 증상도 그걸로 사라지지만, **그건 BD-35가 명시적으로 계산한 비대칭(미탐의 대가가 훨씬 크다)을 뒤집는 것**이라 새 ADR이 필요하다.

이번에 고친 것은 그 결정이 **지켜지지 않고 있던 부분**이다. 폐기가 새면 오탐이든 진짜 유출이든 "전 세션 폐기"가 성립하지 않는다. 그래서 ADR 없이 버그 수정으로 갔다.

## 걸쇠를 서명 단계에 둔 이유

경합을 재현하려면 두 요청의 순서를 고정해야 한다. 저장 호출을 붙드는 것이 직관적이지만, **그 호출이 곧 수정 대상이라 고치고 나면 테스트를 다시 써야 한다** — 그러면 그 테스트는 회귀를 지키지 못한다.

새 `jti`를 만드는 서명은 저장보다 반드시 앞선다. 어느 구현에도 남아 있는 지점이라 여기를 붙들면 창이 열려 있을 때와 닫힌 뒤에 같은 테스트가 서로 다른 판정을 낸다. 실제로 수정을 되돌려 돌려 보고 그것을 확인했다.

그래서 **누가 이기는지는 단정하지 않는다.** 창이 닫히면 승패가 뒤집히는데(수정 전에는 먼저 출발한 쪽이 성공하고, 수정 후에는 뒤따라온 쪽이 성공한다) 그건 계약이 아니다. 계약은 "둘 중 하나만 성공하고, 끝난 뒤 그 회원의 토큰이 하나도 남지 않는다"이다.

## 대역을 빈으로 갈아끼우지 않았다

처음에는 `@MockitoSpyBean`으로 `JwtTokenProvider`를 감쌌다. 테스트는 의도대로 RED이 됐는데 **전체 check에서 다른 테스트 10개가 깨졌다** — `FATAL: sorry, too many clients already`. 빈 오버라이드가 컨텍스트 캐시 키를 바꿔 컨텍스트가 하나 더 뜨고, 그만큼 Hikari 풀이 늘어 공유 PostgreSQL 컨테이너의 연결 상한을 넘긴 것이다.

`JwtTokenProvider`를 상속한 대역을 만들고 `AuthTokenService`를 테스트에서 직접 조립하는 쪽으로 바꿨다. 기존 컨텍스트를 그대로 재사용하므로 컨텍스트가 늘지 않는다. `StubOAuthProvider`·`FastApiProcessStub`처럼 손으로 쓴 대역을 쓰는 이 저장소의 방식과도 맞다.

**빈 오버라이드는 이 저장소에서 공짜가 아니다**는 것을 여기 적어 둔다. 컨테이너를 JVM 하나가 공유하는 구조(BT-01)라 컨텍스트가 늘면 연결이 는다.

## `revokeAll` 반환값도 같이 고쳤다

인덱스 원소 수를 반환하면서 javadoc은 "폐기한 토큰 수"라고 적고 있었다. 만료된 `jti`는 인덱스에 남으므로 두 값이 다를 수 있다. 사용자에게 보이는 결함은 아니지만 **이 값이 `revoked=` 로그로 나가고, 이번 조사에서 실제로 그 숫자를 근거로 판단했다.** 별건으로 미루면 다음 조사도 같은 숫자를 잘못 읽는다.

## 검증

RED을 두 개 만들고 각각 수정 전 실패를 확인했다.

| 테스트 | 수정 전 |
|---|---|
| `AuthTokenServiceRotationRaceTest` | `Expecting empty but was: ["auth:refresh:92001:86cb6df2-…"]` — 폐기 뒤 저장된 토큰이 살아남음 |
| `AuthTokenContractTests.failedRefreshExpiresAuthCookies` | `access_token 쿠키가 내려오지 않았다. 받은 Set-Cookie: []` |

전자는 수정을 되돌려 다시 한 번 실패를 확인했다(순차 호출로 돌아가면 다시 샌다). `./gradlew clean check --no-daemon` **BUILD SUCCESSFUL** — 73개 클래스 486건 전부 통과, 실패·오류·건너뜀 0. Checkstyle과 `jacocoTestCoverageVerification`도 같은 호출에 포함됐다.

## 범위 밖

- **클라이언트가 같은 토큰으로 재발급을 두 번 보낸 원인** — 프론트 쪽이고 별도로 다룬다. 공용 계약 08 §3.3이 "재발급 요청은 동시에 하나만"을 이미 규정한다. 이 작업은 그 계약이 깨졌을 때 서버가 새지 않고 복구 가능한 상태를 남기는 데까지만 책임진다
- **Redis 영속성(PVC + AOF), Alloy 로그 마스킹** — 조사 중 드러났지만 인프라 저장소 사안이다. 전자는 이번 장애의 원인이 아니다
