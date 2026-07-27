# BD-05. `GlobalExceptionHandler`가 `ResponseEntityExceptionHandler`를 상속해 프레임워크 예외를 자체 상태로 매핑한다

- **상태**: Accepted
- **날짜**: 2026-07-27
- **관련**: S15P11A705-40 (`1911dc9`, `cae552e`, `7e31e02`), [BI-05](../implements/BI-05-2026-07-27-framework-error-mapping.md)

## 맥락

전역 예외 처리기(`@RestControllerAdvice GlobalExceptionHandler`)는 `BusinessException`과
Bean Validation 실패(`MethodArgumentNotValidException`) 두 분기만 구체적으로 다루고, 그 외
모든 예외는 catch-all `@ExceptionHandler(Exception.class)`로 흡수해 `500 INTERNAL_ERROR`로
응답했다. 문제는 이 catch-all이 진짜 서버 오류가 아닌 것들까지 잡는다는 데 있다 — malformed
JSON body, 405(허용되지 않은 메서드), 415(지원하지 않는 Content-Type), 필수 query parameter
누락은 전부 Spring MVC가 이미 각자의 프레임워크 예외
(`HttpMessageNotReadableException`/`HttpRequestMethodNotSupportedException`/
`HttpMediaTypeNotSupportedException`/`MissingServletRequestParameterException`)로 구분해
던지는데도, 우리 핸들러에 이들을 위한 구체 분기가 없어 전부 `Exception`에 매칭되어 500으로
보고되었다. 클라이언트 실수가 서버 오류로 집계되면 알림·모니터링·SLO가 오염된다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 예외마다 `@ExceptionHandler`를 개별 추가 | 각 분기가 명시적이고 기존 클래스 상속 구조를 바꾸지 않는다 | Spring MVC가 던지는 프레임워크 예외는 이 네 가지 외에도 다수 있다(`HandlerMethodValidationException` 등). 열거하지 못한 예외는 여전히 catch-all → 500이 되므로, 이 티켓이 없애려는 증상(프레임워크 예외가 500으로 수렴)을 절반만 해결한다 |
| (b) 프레임워크 예외를 전부 400으로 통합 | 공용 API 명세 §1.5의 "권장 상태 코드" 표(400·401·404·409·422)를 벗어나지 않는다 | 405(메서드 불일치)·415(미디어 타입 불일치)는 HTTP 의미상 400과 다른 상태다. 이를 400으로 뭉개면 클라이언트가 `Allow`/`Accept` 헤더 기반으로 재시도 전략을 세울 수 없고, 표준을 어기면서까지 명세 표를 지키는 셈이라 정확성을 명세 부합보다 낮추는 선택이 된다 |
| (c) **채택**: `ResponseEntityExceptionHandler` 상속 + `handleExceptionInternal`에서 body만 교체 | Spring이 이미 알고 있는 프레임워크 예외 → 상태 코드 매핑(20종 이상)을 전부 재사용한다. 열거하지 않은 예외도 부모가 정한 상태로 응답하므로 "미래에 새로 추가되는 프레임워크 예외"에도 안전하다. 우리는 body 포맷(envelope)만 책임진다 | 부모가 이미 `@ExceptionHandler`로 선언한 예외 타입(`MethodArgumentNotValidException`, `NoResourceFoundException` 등)을 자식에서 다시 선언하면 `Ambiguous @ExceptionHandler method mapped` `IllegalStateException`으로 advice 전체가 죽는다(아래 "결과" 참고). 부모의 오버라이드 가능 지점(`protected` 메서드)에 맞춰 로직을 옮겨야 한다 |

## 결정

**(c)**를 채택한다. `GlobalExceptionHandler`가 `ResponseEntityExceptionHandler`를 상속하고,
상태 코드는 부모가 정한 값을 그대로 쓰며 `handleExceptionInternal`을 오버라이드해 body만
공통 envelope(`ApiResponse.fail(ErrorResponse)`)로 교체한다. `ErrorCode`에 `METHOD_NOT_ALLOWED`
(405)·`UNSUPPORTED_MEDIA_TYPE`(415)를 추가하고, 상태 코드 → `ErrorCode` 매핑은
405→`METHOD_NOT_ALLOWED`, 415→`UNSUPPORTED_MEDIA_TYPE`, 404→`RESOURCE_NOT_FOUND`,
그 외 4xx→`INVALID_INPUT`, 5xx→`INTERNAL_ERROR`다.

**브리프 대비 이탈(정당한 이탈로 판단해 그대로 진행)**: 애초 계획은 기존 자식 분기
(`@ExceptionHandler(MethodArgumentNotValidException.class)`, `@ExceptionHandler(NoResourceFoundException.class)`)를
유지한 채 부모만 상속하는 것이었다. 그러나 부모 `ResponseEntityExceptionHandler.handleException`이
이미 이 두 타입을 포함해 20개 예외를 `@ExceptionHandler`로 선언하고 있어, 자식에서 재선언하면
`ExceptionHandlerMethodResolver`가 "Ambiguous @ExceptionHandler method mapped" `IllegalStateException`을
던진다. 실제로 두 분기를 남긴 중간 버전으로 테스트를 돌려 advice 초기화 자체가 실패하고 8개 테스트
전부(신규 4개 + 기존 3개 + 회귀 가드 1개가 아니라, 그 이전 시점 기준 전체)가 죽는 것을 관찰했다.
대신 검증 로직은 부모 시그니처 `handleMethodArgumentNotValid`를 오버라이드해 이관했고(fieldErrors
로직 자체는 동일), 404는 부모의 `handleNoResourceFoundException` → 상태 코드 매핑으로 처리한다
(응답은 이전과 동일: 404, `RESOURCE_NOT_FOUND`, `fieldErrors: []`). `BusinessException` 분기와
catch-all `Exception` 분기는 그대로 남겼다.

## 명세와의 차이(기록)

공용 API 명세(`Team-PinLog/docs`의 `static/08_API_명세.md`) §1.5 "권장 상태 코드" 표에는
405·415가 없다(400·401·404·409·422만 나열). 이번 결정으로 405·415를 실제로 반환하기 시작하면서
이 표에 없는 상태 코드가 응답에 등장한다. HTTP 표준이 정한 의미이고 이전의 500(진짜 서버 오류로
잘못 분류)보다 정확하므로 채택했다. 명세 §1.5 표에 405·415를 추가하는 개정은 이 PR 범위 밖이며
후속 과제로 남긴다 — 별도 레포(`docs`)의 변경이 필요하다.

## 결과

- 이 결정으로 감수하는 것:
  - `ProblemDetail`은 응답 body로 노출되지 않는다. `handleExceptionInternal`이 envelope를 만든
    뒤 `super.handleExceptionInternal`에 위임하므로 body는 항상 non-null이고, 부모의
    `ProblemDetail` 생성 경로가 실행될 조건(`body == null`)이 성립하지 않는다(Spring 7.0.8 소스로
    확인).
  - 4xx는 `WARN`으로 로깅하고 `ERROR`로 로깅하지 않는다. `ERROR`는 5xx에만 남긴다.
  - **행동 변화**: 이제 미매핑 URL(404) 하나마다 `WARN` 로그가 한 줄 남는다. 이전
    `handleNoResource`는 무음이었다. 계약 위반은 아니지만 배포 후 봇 스캐닝 트래픽이 있으면 로그
    소음이 될 수 있다 — 필요해지면 404만 `DEBUG`로 낮추는 것을 재검토 트리거로 남긴다(지금
    선제적으로 처리하지 않음, YAGNI).
  - **부수 개선**: `HandlerMethodValidationException`(예: `@RequestParam`의 `@Min` 위반)이 이전에는
    catch-all에 걸려 500이었으나 이제 부모 매핑을 타 400 `INVALID_INPUT`으로 응답한다. 단
    `fieldErrors`는 빈 배열이다(부모가 body를 null로 위임하고, 우리는 이 예외 전용 오버라이드를
    아직 추가하지 않았기 때문). 실제 도메인 컨트롤러가 파라미터 제약을 쓰기 시작하면
    `handleHandlerMethodValidationException`을 채우는 별도 티켓이 필요하다.
- 재검토 트리거(이 조건이 오면 다시 논의):
  - 명세 §1.5 개정이 이 프로젝트 범위로 들어올 때 405·415를 표에 반영.
  - 404 WARN 로그가 실제 운영 로그 볼륨 문제로 확인될 때.
  - 도메인 컨트롤러가 `@RequestParam`/`@PathVariable` 제약(`@Min` 등)을 쓰기 시작해
    `HandlerMethodValidationException`의 `fieldErrors`가 실제로 필요해질 때.
  - Spring 업그레이드로 `ResponseEntityExceptionHandler`의 `@ExceptionHandler` 목록이 늘어날 때 —
    새로 추가된 예외가 `errorCodeOf`의 기본 폴백(4xx→`INVALID_INPUT`, 5xx→`INTERNAL_ERROR`)이 아닌
    전용 `ErrorCode`가 필요한지 재검토.
