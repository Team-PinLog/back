# BD-49. 인가 요청 쿠키를 Java 직렬화 대신 JSON으로 담는다

- **상태**: Accepted
- **날짜**: 2026-08-04
- **관련**: [S15P11A705-132](https://ssafy.atlassian.net/browse/S15P11A705-132) · [back#175](https://github.com/Team-PinLog/back/issues/175) ·
  [BD-30](BD-30-authorization-request-in-cookie.md)(대체하는 결정) · [BD-48](BD-48-unlink-before-withdrawal.md)(이 쿠키에 탈퇴 판정을 실은 결정)
- **범위**: 직렬화 **형식**만 바꾼다. 쿠키에 담는다는 BD-30의 결정과 쿠키 속성(`HttpOnly`·`SameSite=Lax`·`Path`·수명)은 그대로다.

## 맥락

[BD-30](BD-30-authorization-request-in-cookie.md)이 인가 요청을 쿠키에 담기로 하면서 **(b) Java 직렬화 + 역직렬화 허용목록**을 골랐다. 그때 **(c) JSON**을 이 이유로 기각했다.

> `OAuth2AuthorizationRequest` 재구성 코드를 직접 유지해야 하고, **필드 누락이 조용한 버그가 된다**

그 문서는 재검토 트리거도 함께 적었다.

> Spring Security **메이저 업그레이드** → 직렬화 호환을 먼저 확인하고, 깨지면 (c) JSON으로 옮긴다.

## 결정을 뒤집는 근거

### ① 기각 사유가 사실이 아니게 됐다 — 결정적

Spring Security 7.1.0이 **`OAuth2ClientJacksonModule`**을 제공한다. `OAuth2AuthorizationRequest`의 mixin과 deserializer가 들어 있고, `attributes`·`additionalParameters`까지 복원한다. **재구성 코드를 우리가 유지하지 않는다.**

```
org/springframework/security/oauth2/client/jackson/OAuth2ClientJacksonModule.class
org/springframework/security/oauth2/client/jackson/OAuth2AuthorizationRequestMixin.class
org/springframework/security/oauth2/client/jackson/OAuth2AuthorizationRequestDeserializer.class
```

BD-30의 선택지 표에는 **"쿠키 + 공식 Jackson 모듈"** 행이 없었다. (b)의 장점(필드 누락 없음)과 (c)의 장점(형식 독립)을 함께 갖는 안인데 조사되지 않았다. 재검토가 아니라 **누락의 정정**이다.

### ② 재검토 트리거가 발동했다

Spring Security 7.x다. 그리고 공급자 자신이 이 형식을 지지하지 않는다.

> Spring Security provides a `SERIAL_VERSION_UID` for global serialization of Spring Security classes, though **classes are not intended to be serializable between different versions.**

`SpringSecurityCoreVersion.SERIAL_VERSION_UID`는 **7.0.0에서 제거 예정(deprecated for removal)**으로 표시됐다. 실제 사고 보고도 있다 — 클래스 자체는 안 바뀌었는데 4.0 → 4.1 업그레이드에서 `InvalidClassException`이 났다([spring-security#3918](https://github.com/spring-projects/spring-security/issues/3918)).

### ③ 허용목록은 충분한 완화가 아니다

조작 가능한 쿠키를 `readObject`에 넘기는 것은 [CWE-502](https://cwe.mitre.org/data/definitions/502.html)다. 그 문서가 허용목록에 대해 명시한다.

> Add only acceptable classes to an allowlist. **Note: new gadgets are constantly being discovered, so this alone is not a sufficient mitigation.**

이 경로는 **인증 이전**에 돌고 입력은 전적으로 클라이언트가 준다.

### ④ 위조의 결과가 커졌다

[BD-48](BD-48-unlink-before-withdrawal.md) §③이 이 쿠키에 **탈퇴 대상 회원**(`withdrawal_member_id`)을 실었다. 위조 성공의 결과가 "로그인 실패"에서 **"임의 계정 삭제"**로 올라갔다. ①~③만으로도 충분하지만 우선순위를 올리는 근거다.

## 무엇이 실제로 바뀌었나 — JSON도 직렬화다

**"직렬화를 없앴다"가 아니다.** 셋이 바뀌었고, 그중 첫째가 핵심이다.

| | 전 | 후 |
|---|---|---|
| **복원 메커니즘** | 임의 객체 그래프 복원. `readObject`·`readResolve`·`readExternal`, 컬렉션 재구성 시 `hashCode`/`equals`가 **파싱 도중 실행된다** | 지목한 타입에 값을 주입한다. 파싱 중 임의 코드 실행 지점이 없다 |
| **형식 안정성** | 클래스 구조 + `serialVersionUID` 파생 | 필드 이름 기반. Spring이 지원 API로 유지 |
| **매핑 유지 주체** | 우리 + Java 기본 메커니즘 | Spring Security |

**다형 타입 지정은 남는다.** 실제 쿠키 내용에 `@class`가 있다.

```json
{"@class":"org.springframework...OAuth2AuthorizationRequest",
 "attributes":{"@class":"java.util.Collections$UnmodifiableMap", ...}}
```

Jackson의 다형 타입 지정을 무제한으로 열면 그 자체가 RCE 벡터이므로, 모듈이 `BasicPolymorphicTypeValidator`를 함께 구성한다. 즉 **"공격자가 타입을 지목한다"는 문제가 사라진 것이 아니라 통제 지점이 옮겨간 것**이다 — `ObjectInputFilter` → `PolymorphicTypeValidator`.

**새 통제가 더 좁다는 것이 구현 중에 확인됐다.** `java.lang.Long`이 거부됐다. 옛 필터는 `java.lang.**`을 통째로 열어 두었으므로 통과했을 값이다.

## 함께 정한 것

### `attributes` 값은 문자열로 맞춘다

위 거부 때문에 `withdrawal_member_id`를 `Long`에서 `String`으로 바꿨다. **검증기를 넓히지 않는다** — 이번에 세우는 방어를 스스로 깎는 일이다. Spring 자신이 `attributes`에 넣는 값들(`registration_id`, PKCE `code_verifier`)도 모두 문자열이므로 관행에도 맞는다.

읽는 쪽은 숫자가 아니면 "탈퇴 왕복이 아님"으로 떨어뜨린다. 값을 우리가 넣지만 담기는 곳이 쿠키라서다.

### 배포 순간의 옛 쿠키는 실패로 떨어뜨린다

형식이 바뀌므로 배포 시점에 왕복 중이던 쿠키는 읽히지 않는다. **읽지 못하는 값은 전부 "인가 요청 없음"**이고, Spring이 `authorization_request_not_found`로, 실패 핸들러가 `OAUTH_FAILED`로 돌려보낸다.

영향은 **배포 시점(롤링이면 롤아웃 구간 + 쿠키 수명 10분)에 공급자 화면에 있던 사용자**뿐이고, 그들은 로그인이나 탈퇴를 한 번 다시 하면 된다. 로그인 상태로 쓰고 있는 사용자는 이 쿠키를 갖고 있지 않아 **영향이 없다** — 강제 로그아웃은 없다. 프론트도 변경이 필요 없다. 쿠키 수명 10분을 넘겨 만료됐을 때 이미 발생하던 것과 같은 경로다.

**한 릴리스만 둘 다 읽는 폴백을 두지 않았다.** 그러려면 Java 역직렬화 경로를 한 릴리스 더 유지해야 하는데, 그것이 이번에 없애려는 대상이다.

## 결과

**얻는 것**

- Java 역직렬화 표면 제거. `ObjectInputFilter`가 필요 없어졌다.
- Security 업그레이드가 진행 중 로그인을 깨뜨리지 않는다.
- 통제가 우리가 추측한 패키지 목록에서 공급자가 유지하는 타입 검증으로 바뀌었다.

**감수하는 것**

- 다형 타입 지정과 그 검증기에 의존한다. 검증기가 뚫리면 같은 종류의 문제가 생긴다 — 다만 통과 후 벌어지는 일이 "값 주입"이라 gadget chain보다 범위가 좁다.
- 쿠키 크기가 형식에 따라 달라진다. **실측 2018바이트**로 브라우저 상한 4096의 절반이며 회귀 테스트로 고정했다. `@class`가 붙는 만큼 값이 늘면 빠르게 자란다.

**이번에 함께 닫은 것**

BD-30이 "아직 회귀로 고정되지 않은 것"으로 남긴 둘을 테스트로 고정했다 — **조작된 쿠키의 fail-closed 동작**과 **쿠키 속성**(`HttpOnly`·`SameSite=Lax`·`Path`).

**재검토 트리거**

- 쿠키가 4KB에 근접하면 → BD-30의 (d) Redis + 키 쿠키.
- 로그인 진입에 서버측 폐기·감사가 필요해지면 → 같은 이유로 (d).
