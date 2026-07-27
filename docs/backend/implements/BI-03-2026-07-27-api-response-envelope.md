# BI-03. 성공·오류 응답 공통 봉투(ApiResponse) 구현

- **상태**: ✅ 완료
- **날짜**: 2026-07-27
- **관련**: S15P11A705-53 (`a45475a`, `93f4ad6`, `fe52533`, `86b0dd6`, `a1cd90f`), [BD-03](../decisions/BD-03-api-response-envelope.md)

> 번호 참고: 열린 PR #25가 BI-02를 점유 중이라 이 문서는 BI-03부터 시작한다.

## 산출

- `src/main/java/com/pinlog/pinlogback/global/response/ApiResponse.java` — `record ApiResponse<T>(boolean success, T data, ErrorResponse error)`. `@JsonInclude(NON_NULL)`로 성공 응답엔 `error` 키가, 오류 응답엔 `data` 키가 나타나지 않는다. `ok(T)`/`fail(ErrorResponse)` 정적 팩터리만 공개한다.
- `src/main/java/com/pinlog/pinlogback/global/web/ApiResponseBodyAdvice.java` — `ResponseBodyAdvice<Object>`. `com.pinlog.pinlogback.domain` 이하 컨트롤러의 응답만, 이미 `ApiResponse`가 아닐 때만, Jackson 컨버터로 직렬화될 때만 감싼다. `body == null`은 그대로 둔다(빈 본문 유지).
- `src/main/java/com/pinlog/pinlogback/global/exception/GlobalExceptionHandler.java` — 기존 네 분기(`BusinessException`/`MethodArgumentNotValidException`/`NoResourceFoundException`/`Exception`)가 이제 `ResponseEntity<ApiResponse<Void>>`를 반환하며 본문을 `ApiResponse.fail(error)`로 감싼다. 상태 코드, `ErrorCode` 선택, 로그 레벨은 변경하지 않았다.
- (Task 5) `src/main/java/com/pinlog/pinlogback/global/config/ApiResponseOpenApiCustomizer.java` — springdoc `org.springdoc.core.customizers.OperationCustomizer` 구현 빈(`@Component`). 시그니처는 `Operation customize(Operation operation, HandlerMethod handlerMethod)`(springdoc-openapi-starter-common 3.0.3, jar의 `org.springdoc.core.customizers.OperationCustomizer` 소스로 직접 확인). `ApiResponseBodyAdvice`와 동일한 판정(`handlerMethod.getBeanType().getPackageName()`이 `com.pinlog.pinlogback.domain`으로 시작 + `handlerMethod.getReturnType()`의 선언 타입이 이미 `ApiResponse`가 아님)으로 대상을 고른 뒤, `operation.getResponses()`를 순회해 상태 코드가 `2`로 시작하는 3자리 응답만 골라 그 `content`의 각 미디어 타입 스키마를 `{success: boolean, data: <원래 스키마>}` 객체로 교체한다. 원래 스키마가 `$ref`(`io.swagger.v3.oas.models.media.Schema`)면 그 참조 객체를 `data` 프로퍼티에 그대로 넣으므로 `components.schemas`에 중복 정의가 생기지 않는다. content가 없는 응답(204 등)은 `content == null`로 자연히 건너뛴다. 오류 스키마(`error` 필드) 문서화는 이번 범위에 넣지 않았다.

## 검증

실제 실행 결과는 아래 "게이트 실행" 절 참고. 테스트별 확인 내용은 다음과 같다.

- `ApiResponseTest`(`global/response`) — **운영 Jackson 3 매퍼**(`tools.jackson.databind.json.JsonMapper`, Jackson 2의 `com.fasterxml.jackson.databind.ObjectMapper`가 아님)로 직렬화한 JSON을 직접 문자열 검사. `ok(...)`는 `"success":true`를 담고 `"error"` 키가 없음, `fail(...)`은 `"success":false`를 담고 `"data"` 키가 없음을 확인.
- `ApiResponseBodyAdviceTest`(`global/web`) — standalone `MockMvc` + 테스트 전용 컨트롤러(`src/test`의 `domain.sample.EnvelopeTestController`, 운영 코드에는 없음)로 5가지 분기를 확인: DTO 반환이 감싸짐, 이미 `ApiResponse`인 반환은 이중으로 감싸지지 않음, bare `void` 반환은 빈 본문, `ResponseEntity<Void>`(204)는 빈 본문, `beforeBodyWrite(null, ...)` 직접 호출은 `null`을 반환.
- `DeploymentContractTests`·`OpenApiDocsTests`(회귀 감시, 기존 테스트를 수정하지 않고 그대로 통과) — `/api/core/actuator/health`가 봉투 없이 `{"status":"UP"}` 그대로, `/api/core/actuator/prometheus` 200, 미매핑 URL(`/api/core/not-found`, `/actuator/health`)이 404, `/api/core/v3/api-docs`가 200 + `"openapi"` 키를 포함한 유효한 문서. 커스터마이저 도입 이후에도 그대로 통과 — actuator·springdoc 핸들러가 `com.pinlog.pinlogback.domain` 패키지 밖이라 판정에서 자동 제외됨을 재확인.
- `GlobalExceptionHandler`를 호출하는 기존 오류 경로 테스트들 — 상태 코드·`code` 값은 그대로이고, 응답 JSON 최상위가 `{code,...}` flat에서 `{success:false, error:{code,...}}`로 바뀐 것만 반영해 갱신 확인(Task 3, `a1cd90f`).
- (Task 5) `ApiResponseOpenApiCustomizerTest`(`global/config`) — `EnvelopeTestController`(`src/test`)를 `@Import`로 컨텍스트에 넣고 `/api/core/v3/api-docs`를 실제로 호출해 확인. `dto()`(`domain.sample` 패키지, 평범한 DTO 반환) 오퍼레이션의 200 응답 스키마가 `$ref`(`#/components/schemas/Payload`)이고, 그 참조를 `components.schemas`까지 따라가지 않고 **감싸는 지점**(응답 스키마 자체)에서 이미 `success`/`data` 프로퍼티를 가진 객체로 바뀌어 있음을 확인. `alreadyWrapped()`(선언 반환형이 이미 `ApiResponse<Payload>`)의 200 스키마는 손대지 않고 `#/components/schemas/ApiResponsePayload`로 그대로 남아 이중 감싸기가 없음을 확인(수동 검증, `build/api-docs-debug.json`으로 원본 문서를 직접 떠서 대조). 테스트 컨트롤러가 `produces`를 선언하지 않아 미디어 타입 키가 `application/json`이 아니라 `*/*`로 문서화되는 것도 함께 확인 — 테스트는 특정 미디어 타입 키를 가정하지 않고 `content`의 첫 항목을 사용한다.

## 반복될 함정 (다음 사람에게)

1. **actuator·springdoc을 감싸면 안 된다.** `ApiResponseBodyAdvice.supports()`가 URL 패턴이 아니라 **컨트롤러 패키지**(`com.pinlog.pinlogback.domain` 시작 여부)로 판정하는 것이 바로 이 방어다. URL(`/api/core/**`)로 판정하면 actuator·springdoc 핸들러까지 걸려 헬스체크와 Swagger가 깨진다. 새 판정 로직을 추가할 때도 반드시 패키지 기준을 유지하고, `DeploymentContractTests`·`OpenApiDocsTests`가 계속 통과하는지로 확인한다.
2. **`String` 반환은 Jackson 컨버터를 타지 않아 감싸지지 않는다.** `supports()`는 `AbstractJacksonHttpMessageConverter`(Jackson 3의 컨버터 상위 타입 — Jackson 2의 `AbstractJackson2HttpMessageConverter`가 **아님**, 그 타입으로 조건을 걸면 이 프로젝트의 Jackson 3 스택에서 한 번도 매치되지 않아 advice 자체가 조용히 죽는다)로 컨버터 타입을 확인한다. 컨트롤러가 `String`을 그대로 반환하면 `StringHttpMessageConverter`가 처리하므로 이 조건에 걸리지 않고, 감싸지지 않은 순수 문자열이 그대로 나간다. 문자열 응답이 필요하면 DTO로 감싸서 반환하거나, 이 사실을 알고 의도적으로 봉투 없이 내보내는지 확인한다.
3. **(Task 5) 런타임 판정과 문서 판정은 반드시 같은 코드 경로를 참조해야 한다.** `ApiResponseOpenApiCustomizer`가 `ApiResponseBodyAdvice`와 다른 기준으로 대상을 고르면, 둘 중 하나만 바뀌는 변경이 조용히 문서-런타임 불일치를 재도입한다. 새 판정 조건을 추가할 때는 두 클래스 모두를 함께 검토한다. 또한 `produces`를 선언하지 않은 컨트롤러 메서드는 springdoc이 미디어 타입을 `application/json`이 아니라 `*/*`로 문서화하므로, 스키마를 조회하는 테스트나 도구는 미디어 타입 키를 하드코딩하지 말고 `content`를 순회해야 한다.

## 게이트 실행

```
./gradlew clean check --no-daemon
```

Docker Desktop 실행 상태에서 PostgreSQL/pgvector Testcontainers를 포함해 전체 스위트를 실행했다. 실행 로그의 실제 tail은 작업 리포트(`task-4-report.md`)에 그대로 붙였다 — `BUILD SUCCESSFUL`, 테스트 실패 없음, `DeploymentContractTests`·`OpenApiDocsTests` 포함 전 테스트 통과.

(Task 5) 커스터마이저 추가 이후 다시 `./gradlew clean check --no-daemon`을 실행했다. 실제 tail은 `task-5-report.md`에 그대로 붙였다 — `BUILD SUCCESSFUL`, `OpenApiDocsTests`·`DeploymentContractTests` 포함 전 테스트 통과.
