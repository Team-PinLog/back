# BI-05. 프레임워크 예외를 catch-all 500이 아니라 자체 상태 코드로 매핑

- **상태**: ✅ 완료
- **날짜**: 2026-07-27
- **관련**: S15P11A705-40 (`1911dc9`, `cae552e`, `7e31e02`), [BD-05](../decisions/BD-05-framework-error-mapping.md)

## 산출

- `src/main/java/com/pinlog/pinlogback/global/exception/ErrorCode.java` — `METHOD_NOT_ALLOWED`
  (405, "허용되지 않은 요청 메서드입니다.")·`UNSUPPORTED_MEDIA_TYPE`(415, "지원하지 않는 요청
  형식입니다.") 두 값을 추가. 기존 값(`INVALID_INPUT`·`RESOURCE_NOT_FOUND`·`INTERNAL_ERROR`)은
  변경 없음.
- `src/main/java/com/pinlog/pinlogback/global/exception/GlobalExceptionHandler.java` —
  `extends ResponseEntityExceptionHandler`로 전환. 상태 코드는 부모가 정한 값을 그대로 쓰고,
  `handleExceptionInternal`을 오버라이드해 body를 `ApiResponse.fail(ErrorResponse)` envelope로
  교체한 뒤 `super.handleExceptionInternal`에 위임한다(커밋된 응답 방어 로직 유지 목적). 이미
  envelope인 body(우리 오버라이드가 만든 fieldErrors 응답)는 그대로 통과시킨다. 상태 → `ErrorCode`
  매핑(`errorCodeOf`): 405→`METHOD_NOT_ALLOWED`, 415→`UNSUPPORTED_MEDIA_TYPE`,
  404→`RESOURCE_NOT_FOUND`, 그 외 4xx→`INVALID_INPUT`, 5xx→`INTERNAL_ERROR`. 로깅은
  `statusCode.is5xxServerError()`면 `log.error(..., ex)`(스택 포함), 아니면
  `log.warn("framework error: status={}, type={}")`(스택 없음).
  검증(Bean Validation) 로직은 `handleMethodArgumentNotValid` 오버라이드로 옮겼다 — fieldErrors를
  만드는 로직 자체는 이전 자식 분기와 동일하고, envelope를 만들어 `handleExceptionInternal`에
  body로 넘기는 경로만 바뀌었다. `BusinessException` 분기와 catch-all `Exception` 분기는 그대로
  남아 있다.
- `src/test/java/com/pinlog/pinlogback/global/exception/GlobalExceptionHandlerTest.java` — 신규
  테스트 4개(브리프가 요구한 malformed JSON/405/415/파라미터 누락) + 회귀 가드 1개
  (`noResourceFoundStillReturns404`) + 5xx 분기 커버리지 1개
  (`frameworkServerErrorReturns500AndLogsAtErrorLevel`, 코드 리뷰 반영). 기존 3개
  (`businessExceptionMapsToContract`·`validationFailureReturns400WithFieldErrors`·
  `unhandledExceptionReturns500WithoutLeakingInternals`)는 그대로 유지. 클래스 전체 9개.

## 브리프 대비 이탈 (Step 4 지시와 다르게 진행한 부분)

브리프는 자식의 `@ExceptionHandler(MethodArgumentNotValidException.class)`와
`@ExceptionHandler(NoResourceFoundException.class)` 분기를 "그대로 유지"하라고 지시했으나,
**둘 다 제거했다.** 부모 `ResponseEntityExceptionHandler`가 이미 두 예외 타입을 포함한 20개
타입을 `@ExceptionHandler`로 선언하고 있어, 자식에서 재선언하면
`ExceptionHandlerMethodResolver`가 `IllegalStateException("Ambiguous @ExceptionHandler
method mapped for ...")`을 던져 advice 초기화 자체가 실패한다. 두 분기를 남긴 중간 버전으로
실제 테스트를 돌려 이를 재현했다 — advice 생성 단계에서 죽어 그 시점 전체 테스트가 실패하는
것을 관찰했다. 이 이탈은 [BD-05](../decisions/BD-05-framework-error-mapping.md) "결정"에도
근거와 함께 기록했다.

## 검증 — 실제 실행 결과

### RED (신규 4개 테스트가 실패함을 먼저 확인)

```
./gradlew test --tests "com.pinlog.pinlogback.global.exception.GlobalExceptionHandlerTest" --no-daemon
```

```
GlobalExceptionHandlerTest > unsupportedMethodReturns405() FAILED
    java.lang.AssertionError at GlobalExceptionHandlerTest.java:88
GlobalExceptionHandlerTest > malformedJsonReturns400() FAILED
    java.lang.AssertionError at GlobalExceptionHandlerTest.java:79
GlobalExceptionHandlerTest > unsupportedMediaTypeReturns415() FAILED
    java.lang.AssertionError at GlobalExceptionHandlerTest.java:98
GlobalExceptionHandlerTest > missingRequiredParameterReturns400() FAILED
    java.lang.AssertionError at GlobalExceptionHandlerTest.java:106

7 tests completed, 4 failed
BUILD FAILED in 17s
```

XML 리포트에서 확인한 실제 상태 코드 — 네 케이스 전부 500이었다:
`Status expected:<405> but was:<500>`, `<400> but was:<500>`, `<415> but was:<500>`,
`<400> but was:<500>`. 전환 전 핸들러에 이 네 예외
(`HttpMessageNotReadableException`/`HttpRequestMethodNotSupportedException`/
`HttpMediaTypeNotSupportedException`/`MissingServletRequestParameterException`) 전용 분기가
없어 모두 catch-all `Exception`에 잡혀 `INTERNAL_ERROR` + 500으로 응답했다는 것이 이 티켓이
고치려는 정확한 증상이다.

### GREEN

```
./gradlew test --tests "com.pinlog.pinlogback.global.exception.GlobalExceptionHandlerTest" --no-daemon
BUILD SUCCESSFUL in 23s
```

XML 리포트: `tests="8" skipped="0" failures="0" errors="0"`(당시 8개 — 이후 코드 리뷰 반영으로
9번째 `frameworkServerErrorReturns500AndLogsAtErrorLevel`이 추가됨).

### 로그 레벨 증거 (4xx=WARN, 5xx만 ERROR)

GREEN 실행 stdout에서 `GlobalExceptionHandler` 로거 라인만 집계:

```
1 ERROR ...GlobalExceptionHandler -- unhandled error                                        ← 500 하나만
1 WARN  ...GlobalExceptionHandler -- business error: code=RESOURCE_NOT_FOUND, message=...
1 WARN  ...GlobalExceptionHandler -- framework error: status=400, type=HttpMessageNotReadableException
1 WARN  ...GlobalExceptionHandler -- framework error: status=400, type=MethodArgumentNotValidException
1 WARN  ...GlobalExceptionHandler -- framework error: status=400, type=MissingServletRequestParameterException
1 WARN  ...GlobalExceptionHandler -- framework error: status=404, type=NoResourceFoundException
1 WARN  ...GlobalExceptionHandler -- framework error: status=405, type=HttpRequestMethodNotSupportedException
1 WARN  ...GlobalExceptionHandler -- framework error: status=415, type=HttpMediaTypeNotSupportedException
1 WARN  ...GlobalExceptionHandler -- validation error: [FieldError[field=name, message=must not be blank]]
```

4xx에 `ERROR`가 하나도 없고, `ERROR`는 500 케이스 하나뿐이다.

### 코드 리뷰 반영: 5xx 분기 커버리지 (mutation 검증 포함)

리뷰에서 "`handleExceptionInternal`의 5xx 분기(부모가 500으로 넘기는 프레임워크 예외 경로)에
테스트가 없다"는 지적을 받았다. 응답 본문만으로는 이 경로와 catch-all `handleUnexpected`를
구분할 수 없으므로(둘 다 500 + `INTERNAL_ERROR` + 같은 envelope), 로그 문구
(`"framework error: status=500"` vs `"unhandled error"`)로 경유 분기를 단정하는
`frameworkServerErrorReturns500AndLogsAtErrorLevel`을 추가했다. 실제로 실패할 수 있는 테스트인지
프로덕션 코드를 일시 변형해 확인했다:

- **Mutation 1**(5xx 로깅을 `log.error`→`log.warn`): `9 tests completed, 1 failed`,
  `expected: ERROR but was: WARN`.
- **Mutation 2**(`errorCodeOf`가 5xx도 `INVALID_INPUT`을 반환하도록 변경): `9 tests completed,
  1 failed`, `expected:<INTERNAL_ERROR> but was:<INVALID_INPUT>`.

두 변형 모두 신규 테스트 1개만 실패했다(기존 8개는 이 분기를 전혀 덮지 않는다는 뜻이기도 하다).
변형은 원복했고 `git diff HEAD -- src/main/`이 비어 있음(프로덕션 코드가 승인된 커밋과 바이트
동일)을 확인했다.

## `clean check` 전체 게이트 (이 태스크에서 실행)

```
./gradlew clean check --no-daemon
```

Docker Desktop 실행 중(`docker info --format '{{.ServerVersion}}'` → `29.6.1`)이라 PostgreSQL/pgvector
Testcontainers를 포함한 전체 스위트를 그대로 실행했다.

```
BUILD SUCCESSFUL in 1m 6s
9 actionable tasks: 9 executed
```

회귀 감시 대상:

- `DeploymentContractTests` — `tests="4" failures="0" errors="0"`.
  `unmappedServiceUrlIsNotBlockedByAuthentication`(미매핑 URL `/api/core/not-found` → 404)과
  `actuatorIsNotAvailableOutsideServiceContextPath`(`/actuator/health` → 404) 모두 통과 —
  상속 전환이 404 계약을 500으로 깨지 않았다. `healthEndpointIsAvailableUnderServiceContextPath`도
  통과 — actuator health가 여전히 envelope 없이 `{"status":"UP", ...}`로 응답한다(`/api/core/actuator/health`).
- `OpenApiDocsTests` — `tests="2" failures="0" errors="0"`. `/api/core/v3/api-docs` 200,
  `"openapi"`·`"title":"PinLog Core API"` 포함 확인.
- `GlobalExceptionHandlerTest` — `tests="9" failures="0" errors="0"`(신규 4개 + 기존 3개 +
  회귀 가드 1개 + 5xx 커버리지 1개).
- Checkstyle(`checkstyleMain`·`checkstyleTest`) — 경고 0(`maxWarnings=0`).

실제 실행한 명령과 원본 출력은 이 브랜치의 작업 리포트(`.superpowers/sdd/2026-07-27-framework-error-mapping/task-3-report.md`)에 전체 tail을 그대로 붙였다.

## 반복될 함정 (다음 사람에게)

1. **catch-all `Exception` 핸들러는 구체 핸들러보다 항상 뒤에 적용된다는 전제가 이 설계의
   토대다.** `ExceptionHandlerMethodResolver.getMappedMethod`는 매칭되는 핸들러가 여럿이면
   `ExceptionDepthComparator`로 타입 근접도를 비교해 가장 가까운 타입을 고른다. 그래서
   `HttpMessageNotReadableException`은 부모의 구체 분기가, `RuntimeException("boom")`처럼
   구체 분기가 없는 예외만 우리 catch-all이 잡는다. **이 전제가 깨지는 경우**: 새 프레임워크
   버전에서 예외 계층이 바뀌거나, 누군가 catch-all을 `Throwable`처럼 더 넓은 타입으로 바꾸거나,
   `@Order`/advice 등록 순서를 건드리면 구체 분기가 먼저 매칭되지 않을 수 있다. 그러면 조용히
   다시 500으로 돌아간다 — 응답 body만으로는 구분되지 않으므로(둘 다 같은 envelope) 로그 문구
   기반 테스트(`frameworkServerErrorReturns500AndLogsAtErrorLevel`류)로만 잡힌다.
2. **`handleExceptionInternal`에서 부모가 만든 `ProblemDetail`을 그대로 반환하면 응답 계약이
   깨진다.** 부모의 여러 `handleXxx` 메서드(`handleHttpMessageNotReadable` 등)는
   `createProblemDetail(...)`로 non-null `ProblemDetail`을 body로 만들어 넘긴다. 이를 우리
   오버라이드에서 무조건 `ApiResponse` envelope로 교체하지 않으면(`body instanceof ApiResponse<?>`
   가 아닌 모든 경우를 대체) 클라이언트가 `{"success":false,"error":{...}}` 대신
   `{"type":"about:blank","title":...}` 같은 RFC 7807 형태를 받게 되어 `error.code`/`error.traceId`
   기반 분기가 전부 깨진다. body가 항상 non-null이므로(우리가 항상 값을 채워 넘김) 부모의
   Spring 7 `ProblemDetail` 자동 생성 경로(`body == null`일 때만 도는 분기)가 애초에 실행될
   조건이 성립하지 않는다는 점을 실제 Spring 7.0.8 소스로 확인했다 — 이 전제를 지키려면
   `handleExceptionInternal`을 손댈 때 항상 body를 채워서 `super`에 넘겨야 한다.

## 우려사항 (후속 과제 후보)

1. 미매핑 URL(404)마다 `WARN` 로그 한 줄이 남는다(이전 `handleNoResource`는 무음). 봇 스캐닝
   트래픽이 많은 환경에서는 로그 노이즈가 될 수 있다 — 필요해지면 404만 `DEBUG`로 낮추는 조정이
   후속 과제다.
2. `HandlerMethodValidationException`(예: `@RequestParam`의 `@Min` 위반)이 이전에는 catch-all에
   걸려 500이었으나 이제 400 `INVALID_INPUT`으로 간다(부수 개선). 다만 `fieldErrors`는 빈
   배열이다(부모가 body를 null로 위임하고 이 예외 전용 오버라이드는 아직 없기 때문). 도메인
   컨트롤러가 파라미터 제약을 실제로 쓰기 시작하면 `handleHandlerMethodValidationException`을
   채우는 후속 티켓이 필요하다.
