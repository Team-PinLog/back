# 에러 처리 규약

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 [API 규약](api-conventions.md)이 정의한 오류 응답 계약을 **어떻게 구현하는가**의 기준입니다.

## 오류 응답 계약 (재확인)

모든 오류 응답은 [API 규약](api-conventions.md)의 공통 envelope를 지킵니다. 최상위는 `{ "success": false, "error": {…} }`이며, `error` 안의 필드는 다음과 같습니다.

| 필드 | 의미 |
| --- | --- |
| `code` | 클라이언트가 분기할 수 있는 안정적인 오류 코드 |
| `message` | 호출자가 이해할 수 있는 오류 설명 |
| `fieldErrors` | 검증(Bean Validation) 실패 시 필드별 위반을 담는 배열, 그 외에는 빈 배열 |
| `traceId` | 로그와 요청을 잇는 추적 식별자 |

`success`/`error` envelope는 `global/response/ApiResponse.fail(...)`이 생성하며, `global/exception/GlobalExceptionHandler`의 각 분기가 이를 반환합니다.

> `Team-PinLog/docs`의 `static/08_API_명세.md` §5.6·§5.7은 409 충돌 응답에 `error.impact`(예: `DELETE_CONFIRMATION_REQUIRED`) 필드를 명세하지만, 이 필드는 **아직 구현하지 않았습니다**. Record 삭제 티켓에서 `ErrorResponse`를 확장해 도입할 예정입니다.

이 문서는 이 계약을 **한 곳에서 일관되게** 생성하는 방법을 정의합니다. 컨트롤러마다 제각각 오류 응답을 만들지 않습니다.

## 원칙

- 오류 응답은 **전역 핸들러 한 곳**에서 생성합니다. 개별 컨트롤러에서 오류 JSON을 직접 조립하지 않습니다.
- 내부 구현을 노출하지 않습니다 — 스택 트레이스, SQL, 예외 클래스명, 원본 메시지를 응답 body에 담지 않습니다. 상세는 로그(`traceId`로 연결)에만 남깁니다.
- `message`는 호출자를 위한 설명이고, 분기 기준은 항상 `code`입니다. 클라이언트는 `message` 문자열에 의존하지 않습니다.
- 새 오류를 추가할 때는 **상태 코드 + `code` + 발생 조건 + 테스트**를 함께 추가합니다.

## 예외 계층

도메인 오류는 공통 베이스 예외를 상속해, **오류 코드와 HTTP 상태를 예외가 들고** 다니게 합니다. 핸들러는 이 정보를 응답으로 변환하기만 합니다.

- 베이스 예외는 `global/exception`에 둡니다([패키지 구조 규약](package-structure.md)).
- 베이스 예외는 최소한 **오류 코드**와 **HTTP 상태**를 보유합니다.
- 도메인별 구체 예외는 해당 도메인에서 베이스를 상속합니다. 예: `record` 도메인의 "기록 없음"은 `404` + 안정 코드.
- 예상 가능한 실패에는 구체 예외를 사용하고, 예상 밖 예외는 아래 "처리되지 않은 예외"로 흡수합니다.

## 오류 코드 레지스트리

`code`는 **안정적이고 유일**해야 합니다. 한 번 배포된 코드 값은 의미를 바꾸지 않습니다(클라이언트 분기가 깨짐).

- 코드는 한 곳(enum 등)에 모아 관리하고, 문자열을 흩뿌리지 않습니다.

> **현재 `ErrorCode`에는 401·403·409에 대응하는 코드가 없습니다.** 매핑이 없는 4xx는 `errorCodeOf`의 폴백으로 `INVALID_INPUT`(400 문구)이 되므로, 401·403을 쓰는 PR은 **해당 코드를 먼저 추가**해야 합니다. 그렇지 않으면 상태 코드는 403인데 `code`는 `INVALID_INPUT`으로 나가 클라이언트 분기가 깨집니다.

- 도메인을 접두어로 구분하는 것을 권장합니다. 예: `MEMBER_NOT_FOUND`, `RECORD_ACCESS_DENIED`.
- 코드마다 HTTP 상태와 발생 조건을 이 문서 또는 코드 주석에 기록합니다.
- 값 변경이 필요하면 옛 코드를 없애지 말고 **새 코드를 추가**한 뒤 마이그레이션합니다.

## 전역 핸들러

`@RestControllerAdvice` 한 곳에서 예외를 응답으로 변환합니다.

- **도메인 베이스 예외** → 예외가 든 `code`와 상태로 응답을 만듭니다.
- **검증 실패**(Bean Validation) → `400`, 같은 오류 계약을 사용합니다(아래).
- **처리되지 않은 예외** → `500`, 일반 `code`(예: `INTERNAL_ERROR`)와 무해한 `message`만 반환하고, 원인은 `traceId`와 함께 로그에 남깁니다.

`@RestControllerAdvice` 밖에서 직접 쓰여지는 응답 — Spring Security의 401/403 entry point·handler, Boot의 `/error` 폴백 — 은 이 공통 envelope를 거치지 않습니다. 인증 작업에서는 이런 컴포넌트도 `ApiResponse.fail(...)`을 직접 만들어 반환해야 합니다.

## 검증 오류 (400)

Bean Validation 실패는 [API 규약](api-conventions.md)대로 **HTTP 400**으로, 공통 오류 계약을 지켜 반환합니다.

- 필드 단위 위반을 담을 때도 최상위 형태(`code`/`message`/`traceId`)는 동일하게 유지합니다.
- 어떤 필드가 왜 실패했는지는 클라이언트가 다룰 수 있는 형태로 제공하되, 내부 구현은 노출하지 않습니다.

## traceId

`traceId`는 응답과 로그를 잇는 식별자입니다. 요청마다 하나를 확보해 응답 계약과 로그에 **같은 값**을 사용합니다. 생성·전파 방식(요청 필터, MDC 등)은 [로깅 규약](logging.md)에서 정의합니다.

## 상태 코드 매핑 (기준)

| 상황 | 상태 |
| --- | --- |
| 검증 실패 | `400` |
| 미인증(쿠키 없음·만료, 회전 전 Refresh 재사용) | `401` (인증 도입 시, [인증 PR 계약](authentication.md)) |
| CSRF 토큰 누락·불일치 | `403` — **`403`은 이 용도로만 씁니다** |
| 권한 부족 | `404` (리소스 은닉 정책 확정. 존재 여부를 노출하지 않습니다) |
| 리소스 없음 | `404` |
| 도메인 규칙 위반(충돌 등) | `409` 등 상황에 맞는 4xx |
| 처리되지 않은 예외 | `500` |

`403`과 `404`가 한 상태 코드에 두 의미를 갖지 않도록 용도를 갈라 지킵니다 — 자원 접근 권한 실패는 `404`, CSRF 실패는 `403`입니다. 근거는 [08_API_명세 §1](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)이며 [BD-21](../backend/decisions/BD-21-auth-token-model.md)이 감수 항목으로 기록했습니다.

> 결정 배경: [BD-13](../backend/decisions/BD-13-public-boundary-query-dto-split.md) 403 대신 404를 쓰는 이유와 공개 경계 · [BD-11](../backend/decisions/BD-11-minimum-holding-invariants.md) 409에 `error.impact`를 실어 연쇄 삭제 범위를 알리는 이유 · [BD-03](../backend/decisions/BD-03-api-response-envelope.md) 오류 응답 envelope

### 프레임워크 예외 매핑

| 상황 | 상태 | code |
| --- | --- | --- |
| 요청 body 파싱 실패(malformed JSON) | `400` | `INVALID_INPUT` |
| 필수 query parameter 누락 | `400` | `INVALID_INPUT` |
| 허용되지 않은 HTTP 메서드 | `405` | `METHOD_NOT_ALLOWED` |
| 지원하지 않는 Content-Type | `415` | `UNSUPPORTED_MEDIA_TYPE` |

`GlobalExceptionHandler`는 `ResponseEntityExceptionHandler`를 상속해 Spring이 이미 알고 있는
프레임워크 예외 → 상태 코드 매핑을 그대로 재사용하고, `handleExceptionInternal`에서 body만 공통
envelope로 교체합니다. 상태 코드 자체는 바꾸지 않으므로 위 표에 열거하지 않은 프레임워크 예외도
(예: Spring이 향후 버전에서 새로 던지는 예외) catch-all `500`으로 뭉개지지 않고, 부모가 정한 상태로
응답합니다 — 다만 그 상태에 대응하는 `ErrorCode`가 레지스트리에 없으면 4xx는 `INVALID_INPUT`, 5xx는
`INTERNAL_ERROR`로 폴백합니다(자세한 근거는 [BD-06](../backend/decisions/BD-06-framework-error-mapping.md)).

## 테스트

오류 경로도 테스트합니다([테스트 규약](testing-conventions.md)).

- 새 `code`를 추가하면 **상태 코드 + `code` + 발생 조건**을 검증하는 테스트를 함께 추가합니다.
- 검증 실패가 `400`과 공통 계약으로 반환되는지 테스트합니다.
- 처리되지 않은 예외가 내부 정보를 누출하지 않고 `500` 계약으로 응답하는지 확인합니다.

## 체크리스트

- [ ] 오류 응답은 전역 핸들러 한 곳에서 생성한다
- [ ] 도메인 예외는 베이스를 상속하고 코드·상태를 보유한다
- [ ] `code`는 레지스트리 한 곳에서 관리하고 안정적이다
- [ ] 스택 트레이스·SQL·예외 클래스명을 응답에 노출하지 않는다
- [ ] 검증 실패는 `400` + 공통 계약으로 반환한다
- [ ] 새 오류마다 상태·코드·조건·테스트를 함께 추가한다
