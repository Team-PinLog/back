# BI-40. 인가 요청 쿠키를 JSON으로 전환하고 Java 역직렬화를 걷어냄

- **상태**: ✅ 완료
- **날짜**: 2026-08-04
- **관련**: S15P11A705-132, [back#175](https://github.com/Team-PinLog/back/issues/175),
  [BD-49](../decisions/BD-49-authorization-request-cookie-json.md)(결정),
  [BD-30](../decisions/BD-30-authorization-request-in-cookie.md)(대체된 결정),
  [BD-48](../decisions/BD-48-unlink-before-withdrawal.md)(이 쿠키에 탈퇴 판정을 실은 결정)

## 산출

- `CookieOAuth2AuthorizationRequestRepository` — `ObjectOutputStream`/`ObjectInputStream`/`ObjectInputFilter` 제거. `SecurityJacksonModules.getModules(classLoader)`로 만든 전용 `JsonMapper`로 직렬화·역직렬화한다. 공개 계약(메서드 시그니처·쿠키 이름·속성·`consumedAuthorizationRequest`)은 그대로다.
- `WithdrawalAwareAuthorizationRequestResolver` — `attributes`의 `withdrawal_member_id`를 `Long` → `String`. 읽는 쪽에 숫자 파싱과 실패 처리.
- 테스트 6건 추가 — JSON 형식 · PKCE 생존 · 조작 쿠키 fail-closed · 옛 형식 쿠키 fail-closed · 쿠키 속성 · 쿠키 크기.

전용 mapper를 쓰는 이유는 이 모듈들이 **다형 타입 검증을 켜기 때문**이다. 애플리케이션 공용 mapper에 그 설정이 번지면 안 된다.

## 설계 판단

### 직렬화를 없앤 것이 아니다 — 메커니즘을 바꿨다

JSON도 직렬화다. 바뀐 것은 **복원 메커니즘**이다. `readObject`는 임의 객체 그래프를 복원하면서 `readObject`·`readResolve`·`readExternal`과 컬렉션 재구성 시의 `hashCode`/`equals`를 **파싱 도중 실행한다**. Jackson은 지목한 타입에 값을 넣을 뿐이다.

다만 다형 타입 지정은 남는다(쿠키에 `@class`가 실린다). **통제 지점이 `ObjectInputFilter`에서 `PolymorphicTypeValidator`로 옮겨간 것**이며, 근거와 남는 위험은 BD-49에 있다.

### 검증기를 넓히지 않고 값을 맞췄다

구현 중 `java.lang.Long`이 검증기에 거부됐다. 검증기를 넓히는 것은 이번에 세우는 방어를 스스로 깎는 일이라, `withdrawal_member_id`를 문자열로 바꿨다. Spring 자신이 `attributes`에 넣는 값들도 모두 문자열이다.

**이 거부가 새 통제의 우위를 보여 준다** — 옛 필터는 `java.lang.**`을 통째로 열어 두어 그냥 통과했을 값이다.

### 폴백을 두지 않았다

한 릴리스만 "JSON 실패 시 Java 역직렬화" 폴백을 두면 배포 시점 실패가 0이 된다. 그러려면 **없애려는 그 경로를 한 릴리스 더 유지**해야 한다. 영향이 "배포 시점 공급자 화면에 있던 소수가 한 번 재시도"라 대가가 더 크다고 봤다.

## 검증

`./gradlew clean check --no-daemon` 통과.

| 테스트 | 고정한 것 |
|---|---|
| `theCookieCarriesJsonNotAJavaSerializationStream` | base64 디코드 결과가 `{`로 시작한다 — Java 직렬화 매직(`0xACED`)이 아니다 |
| `pkceVerifierSurvivesTheRoundTrip` | `code_verifier`·`code_challenge`·`method`가 왕복에서 살아남는다 |
| `roundTrip` | `withdrawal_member_id`가 살아남는다 — **이 테스트가 `Long` 거부를 잡았다** |
| `tamperedCookieFailsClosed` | 조작 쿠키 → `null`. 예외가 필터 체인 밖으로 새지 않는다 |
| `cookieFromAnotherFormatFailsClosed` | 옛 Java 직렬화 쿠키 → `null`(배포 직후 경로) |
| `cookieAttributesAreFixed` | `HttpOnly`·`SameSite=Lax`·`Path` |
| `theCookieStaysWellUnderTheBrowserLimit` | 실측 **2018바이트** < 4096 |

뒤에서 셋째·넷째는 **BD-30이 "아직 회귀로 고정되지 않은 것"으로 남긴 항목**이다. 이번에 닫혔다.

기존 회귀망도 그대로 통과한다 — `WithdrawalAwareAuthorizationRequestResolverTest`·`OAuthCallbackWithdrawalBranchTest`·`ProviderTokenReachesSuccessHandlerTest`·`OAuthCallbackCancellationTest`가 `attributes`와 PKCE를 이미 검증하고 있다.

## 남긴 것

**로그인·탈퇴 성공 왕복을 실물로 확인하지 않았다.** 실제 공급자 동의가 필요하다. 단위·통합 테스트가 형식 왕복과 콜백 분기를 덮고 있으므로, 스테이징 확인 항목에 **배포 직후 로그인 1회**를 더한다.

**쿠키 크기는 형식에 민감하다.** 2018바이트는 여유가 있지만 `@class`가 붙는 만큼 값이 늘면 빠르게 자란다. 4KB에 근접하면 BD-30의 (d) Redis + 키 쿠키로 옮긴다.
