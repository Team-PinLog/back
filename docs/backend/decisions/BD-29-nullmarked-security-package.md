# BD-27. `global/security` 패키지를 `@NullMarked`로 선언한다

- **상태**: Accepted
- **날짜**: 2026-07-28
- **관련**: S15P11A705-63 · `f026b15`

## 맥락

Spring Framework 7·Security 7·Data 4가 자기 패키지를 JSpecify `@NullMarked`로 선언한다. 확인한 사실은 다음과 같다.

```text
org.springframework.security.oauth2.client.web   package-info → @NullMarked
  AuthorizationRequestRepository
    loadAuthorizationRequest()    → @Nullable 반환
    removeAuthorizationRequest()  → @Nullable 반환
    saveAuthorizationRequest(T, HttpServletRequest, HttpServletResponse)
                                  → 파라미터에 @Nullable 없음(= non-null)
```

우리가 이 인터페이스를 구현하면서 두 가지 경고가 났다.

1. `null`을 반환하는 메서드에 `@Nullable` 표기가 없다
2. **표기 없는 파라미터가 `@NullMarked` 파라미터를 재정의한다** — `HttpServletRequest`·`HttpServletResponse`까지 포함

2번이 핵심이다. 우리 패키지가 마킹되지 않아 파라미터의 nullness가 "미상"으로 남고, non-null로 선언된 상위 파라미터를 재정의할 때 보증이 약해진다. 개별 메서드를 고쳐서는 사라지지 않는다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 경고를 무시한다 | 작업 없음 | 프레임워크가 제공하는 nullness 정보를 못 쓴다. 경고에 묻혀 진짜 문제를 놓친다 |
| (b) 파라미터마다 `@NonNull` | 국소적 | JSpecify의 설계 의도와 반대다. non-null이 기본이고 예외를 표기하는 방식인데, 모든 파라미터에 표기가 붙어 소음이 된다. 새 메서드마다 반복 |
| (c) 클래스마다 `@NullMarked` | 파일 단위로 제어 | 클래스가 늘 때마다 붙여야 하고 빠뜨리기 쉽다 |
| **(d) 패키지에 `@NullMarked`** | JSpecify 권장 단위. 새 클래스에 자동 적용. 파일 하나로 패키지 전체 해결 | 마킹 시점에 nullable 지점을 전수 감사해야 한다 |
| (e) 레포 전체를 `@NullMarked` | 일관성 최대 | 다른 파트 소유 파일까지 감사해야 한다. `global/web`의 `ApiResponseBodyAdvice`는 `@Nullable body`를 받아 마킹 시 거짓 보증이 된다 |

## 결정

**(d)를 채택한다.** `global/security/package-info.java`에 `@NullMarked`를 선언한다.

결정적 이유 둘이다.

- 경고의 원인이 "구현 쪽이 미마킹"이므로 **마킹이 근본 해결**이다. (b)는 증상만 덮고 새 메서드마다 반복된다.
- 이 패키지는 인증 PR에서 새로 만든 6개 클래스로만 이뤄져 있어 **전수 감사 비용이 작다**. 다른 파트 소유 파일이 섞이지 않는다.

마킹하면서 실제로 두 곳을 바로잡았다. 이것이 마킹의 실익이다.

- `SecurityErrorWriter.traceId()` — `MDC.get()`은 `null`을 돌려줄 수 있다. 마킹 후 표기가 없으면 non-null 선언이 되어 거짓 보증이 된다. `@Nullable`을 붙였다.
- `saveAuthorizationRequest`의 첫 파라미터 — 인터페이스는 non-null로 선언하지만 Spring 기본 구현은 `null`을 "저장할 것이 없으니 지운다"로 받는다. 오버라이드에서 파라미터의 nullability를 **넓히는 것은 허용**되므로 `@Nullable`을 붙여 방어 분기를 유지했다.

좁히기(narrowing) 위반이 없는지도 확인했다. 구현하는 네 인터페이스(`AuthenticationEntryPoint`·`AccessDeniedHandler`·`AuthenticationSuccessHandler`·`AuthenticationFailureHandler`)에 `@Nullable` 파라미터가 하나도 없어, 우리 쪽 non-null 선언과 어긋나지 않는다.

`org.jspecify:jspecify`는 `build.gradle`에 명시했다. 애노테이션을 직접 import 하므로 전이 의존에 기대지 않는다. 버전은 Boot BOM이 관리한다.

## 결과

- **이 결정으로 감수하는 것**
  - `global/security`에 클래스를 추가할 때 nullable 지점을 반드시 표기해야 한다. 빠뜨리면 컴파일은 통과하지만 **거짓 보증**이 남는다.
  - 레포 안에 마킹된 패키지와 아닌 패키지가 섞인다. `domain/member/repository`는 Spring Data가 `@NullMarked`이나 `CrudRepository`에 `@Nullable`이 없어 우리 시그니처가 이미 계약과 일치하므로 손대지 않았다.
  - `global/web`(`TraceIdFilter`)에는 같은 경고가 남는다. 같은 패키지의 `ApiResponseBodyAdvice`가 `@Nullable body`를 받아 함께 감사해야 하고, 두 파일 모두 다른 파트가 만든 것이라 이번 범위에서 제외했다.

- **재검토 트리거**
  - `global/web` 정리를 다른 파트와 합의했을 때 — 그 패키지도 같은 방식으로 마킹한다.
  - 마킹 패키지가 늘어 혼재가 부담이 될 때 — 레포 전체 도입((e))을 논의한다.
  - 빌드에 nullness 검사기(NullAway 등)를 도입할 때 — 마킹 범위가 검사 범위가 되므로 전략을 다시 정한다.
