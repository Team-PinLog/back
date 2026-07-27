# BD-03. 성공·오류 응답을 공통 봉투(ApiResponse)로 통일

- **상태**: Accepted
- **날짜**: 2026-07-27
- **관련**: S15P11A705-53 (`a45475a`, `93f4ad6`, `fe52533`, `86b0dd6`, `a1cd90f`), [BI-03](../implements/BI-03-2026-07-27-api-response-envelope.md), `Team-PinLog/docs` PR #11

> 번호 참고: 열린 PR #25가 아직 `dev`에 머지되지 않은 채 BD-02·BI-02·BT-01을 점유하고 있다. 이 브랜치의 `docs/backend/`에는 BD-02가 보이지 않지만, 번호 충돌을 피하기 위해 이 문서는 BD-03부터 시작한다.

## 맥락

이전에는 성공 응답이 컨트롤러 DTO를 그대로 직렬화한 것이고, 오류 응답은 `GlobalExceptionHandler`가 `{code, message, fieldErrors, traceId}` 형태의 flat JSON을 만들었다. 즉 클라이언트 입장에서 "이 응답이 성공인지 오류인지"를 최상위 형태만으로 구분할 방법이 없고, 성공/오류 두 갈래의 파싱 경로를 각각 구현해야 했다. `Team-PinLog/docs` §1.6(공용 API 명세)은 이미 `{success, data}`/`{success, error}` 공통 봉투를 전제로 하고 있어, 백엔드 구현이 명세를 따라가지 못하는 상태였다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 봉투 없이 유지(직전 상태) | 명세 이전 상태와 일치했고, 컨트롤러가 DTO를 그대로 반환해 OpenAPI 스키마가 깔끔함 | 성공/오류 파싱 경로가 갈려 클라이언트가 `success` 필드로 분기할 수 없음 |
| (b) 성공만 감싸고 오류는 flat 유지 | 변경 범위가 작음(Advice만 추가) | 봉투를 도입하는 명분(단일 분기 기준)이 정확히 오류 쪽에서 사라짐 — 결국 두 파싱 경로가 남는 문제를 반만 푼 것 |
| (c) 컨트롤러가 직접 `ApiResponse.ok(...)` 반환 | 감싸는 지점이 명시적이라 추적하기 쉬움 | 신규 엔드포인트마다 누락 위험, 모든 컨트롤러 메서드에 동일한 보일러플레이트 반복 |
| (d) **채택**: 오류까지 같은 봉투로 통일 + `ApiResponseBodyAdvice`가 성공 응답을 자동으로 감쌈 | `success` 필드 하나로 클라이언트가 항상 분기 가능. 컨트롤러는 DTO만 반환하면 되어 신규 엔드포인트에 누락 위험 없음 | 아래 "결과" 참고 |

## 결정

**(d)를 채택한다.** 성공은 `ApiResponse.ok(data)`, 오류는 `ApiResponse.fail(error)`로 같은 `record ApiResponse<T>{success, data, error}`를 쓰고, `@JsonInclude(NON_NULL)`로 성공 응답에는 `error` 키가, 오류 응답에는 `data` 키가 아예 나타나지 않게 한다. 성공 쪽 감싸기는 컨트롤러가 아니라 `global/web/ApiResponseBodyAdvice`(`ResponseBodyAdvice`)가 응답 직렬화 직전에 수행하므로, 컨트롤러는 지금까지처럼 DTO(또는 `void`)만 반환하면 된다. 성공 응답에는 `message` 필드를 두지 않는다 — 사람이 읽을 문구가 필요하면 `data` 안 도메인 필드로 표현한다.

**판정 방식**: `ApiResponseBodyAdvice.supports()`는 URL 패턴이 아니라 **컨트롤러 패키지**(`com.pinlog.pinlogback.domain` 이하)로 감쌀 대상을 정한다. actuator(`org.springframework.boot.actuate.*`)와 springdoc(`org.springdoc.*`)은 이 패키지 밖의 핸들러이므로 이 조건만으로 자동 제외된다. URL 프리픽스로 판정했다면 `/api/core` 하위 전체가 걸려 헬스체크(`/api/core/actuator/health`)와 Swagger(`/api/core/v3/api-docs`)까지 감싸 버렸을 것이다 — 둘 다 명세에 없는 `{success, data}` 껍질이 씌워지면 배포 헬스체크와 API 문서 도구가 깨진다.

## 결과

- 감수하는 것: `success`가 HTTP 상태 코드와 의미상 중복(둘 다 성공/실패를 나타냄). `ApiResponseBodyAdvice`는 런타임에 응답을 감싸는 반면 springdoc은 컨트롤러의 선언된 반환 타입을 그대로 introspect하고 `OperationCustomizer`/`ModelConverter`는 추가하지 않았으므로(`global/config/OpenApiConfig.java`는 `Info`만 설정), springdoc이 생성하는 스키마에는 봉투가 반영되지 않는다 — 스키마가 한 단계 깊어지는 게 아니라 실제 응답과 문서가 어긋난다.
- 규약으로 승격: [`docs/development/api-conventions.md`](../../development/api-conventions.md) "공통 응답 봉투" 절, [`docs/development/error-handling.md`](../../development/error-handling.md) 오류 응답 계약 절.
- 명세 동반 개정: `Team-PinLog/docs` PR #11 — §1.6(봉투 정의), §1.5(오류 shape + `traceId`), §11.0(`ApiResponse<T>`/`ApiError` TypeScript 타입). 이 백엔드 PR은 docs PR #11과 함께 머지되어야 한다.
- 이번에 도입하지 않은 것: §5.6·§5.7이 명세하는 `error.impact`(409 `DELETE_CONFIRMATION_REQUIRED`)는 아직 구현하지 않았다. Record 삭제 티켓에서 `ErrorResponse`를 확장해 도입한다.
- 재검토 트리거: `success` 필드의 중복이 실질적인 버그(상태 코드와 불일치하는 사례)로 이어지거나, 첫 도메인 엔드포인트가 추가될 때. 후자는 springdoc 스키마가 실제 응답(봉투 포함)과 어긋나 FE 코드 생성이 `{success, data}` 없는 타입을 만들게 되므로, 그 시점에 springdoc 커스터마이저(`OperationCustomizer`/`ModelConverter`)를 함께 도입해야 한다.
