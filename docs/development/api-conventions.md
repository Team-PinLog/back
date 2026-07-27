# API 개발 규약

시작 절차와 PR 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 HTTP API를 추가하거나 변경할 때의 상세 기준입니다.

## 경로와 리소스

- 애플리케이션 context path는 `/api/core`입니다.
- Controller의 `@RequestMapping`과 메서드 매핑에는 `/api/core`를 중복하지 않습니다. 예를 들어 회원 리소스는 `/members`로 매핑해 최종 경로를 `/api/core/members`로 만듭니다.
- URI는 복수 명사를 사용합니다. 동작이 필요한 경우에도 리소스와 하위 리소스로 표현하는 방식을 먼저 선택합니다.

## 요청과 응답 모델

- JPA Entity를 request 또는 response DTO로 직접 노출하지 않습니다. Entity, request DTO, response DTO는 각각의 변경 이유에 맞게 분리합니다.
- 시간 값은 ISO-8601 UTC 형식으로 주고받습니다. 예: `2026-07-23T14:06:24Z`.
- Bean Validation 실패는 HTTP 400으로 응답합니다.
- 목록 pagination은 커서 기반이며, query parameter는 `cursor`·`size`를 사용합니다. `page`/`sort` 파라미터는 사용하지 않습니다.

### 공통 응답 envelope

모든 API 응답은 최상위가 공통 envelope입니다. 성공은 `{ "success": true, "data": … }`, 오류는 `{ "success": false, "error": {…} }`이며 두 키는 동시에 나타나지 않습니다(오류 계약은 [에러 처리 규약](error-handling.md) 참고). 공용 명세 원본은 `Team-PinLog/docs`의 `static/08_API_명세.md` §1.6입니다.

- 컨트롤러는 **DTO(또는 `void`)를 그대로 반환**합니다. `com.pinlog.pinlogback.domain` 이하 컨트롤러의 응답은 `global/web/ApiResponseBodyAdvice`가 자동으로 `ApiResponse`로 감쌉니다. 컨트롤러에서 직접 `ApiResponse.ok(...)`를 만들어 반환하지 않습니다.
- 성공 응답에는 `message` 필드를 두지 않습니다. 사람이 읽을 문구가 필요하면 `data` 안의 도메인 필드로 표현합니다.
- 목록 응답은 `data` 안에 `items`(배열)·`nextCursor`·`hasNext`를 담는 커서 기반 형태를 사용합니다. 요청은 `cursor`·`size` 쿼리 파라미터로만 받으며, 응답도 offset이 아니라 커서(`nextCursor`)로 이어집니다.
- `204 No Content`는 envelope를 포함해 본문이 전혀 없습니다.

#### 커서 페이지네이션 구현 사실

명세 §1.4가 정한 `cursor`·`size` 계약을 아래 타입으로 구현했습니다(결정 배경은 [BD-04](../backend/decisions/BD-04-cursor-pagination.md) 참고).

- 목록 응답 타입은 `global/response/CursorPage<T>`이며 `ApiResponse`의 `data`에 담깁니다.
- 커서는 `global/response/Cursor`가 만듭니다 — `Base64(정렬키,id)`, URL-safe·패딩 없음. **클라이언트는 해석하지 않습니다.**
- `size` 기본값은 `CursorPage.DEFAULT_SIZE`(20), 서버 방어 상한은 `CursorPage.MAX_SIZE`(100)입니다. 범위 밖 값은 `CursorPage.normalizeSize(Integer)`가 보정합니다(`null`·0·음수 → 기본값, 상한 초과 → 상한).
- 잘못되거나(Base64가 아님·구분자 없음·id가 숫자가 아님) 비어 있는(`null`·빈 문자열) 커서는 `400`(`INVALID_INPUT`)으로 거절됩니다.
- 마지막 페이지에서도 `nextCursor`는 키가 사라지지 않고 명시적으로 `null`로 노출됩니다.

## 오류 계약

공통 오류 응답에는 항상 다음 필드를 제공합니다.

| 필드 | 의미 |
| --- | --- |
| `code` | 클라이언트가 분기할 수 있는 안정적인 오류 코드 |
| `message` | 사용자 또는 호출자가 이해할 수 있는 오류 설명 |
| `traceId` | 로그와 요청을 연결하는 추적 식별자 |

새 오류를 추가할 때는 상태 코드, `code`, 발생 조건과 API 테스트를 함께 추가합니다. validation 오류도 이 공통 오류 계약을 지켜 HTTP 400으로 반환합니다.

> 결정 배경: [BD-03](../backend/decisions/BD-03-api-response-envelope.md) 공통 응답 envelope · [BD-04](../backend/decisions/BD-04-cursor-pagination.md) 커서 페이지네이션 · [BD-12](../backend/decisions/BD-12-public-boundary-query-dto-split.md) 권한 실패에 403이 아닌 404를 쓰는 이유 · [BD-10](../backend/decisions/BD-10-minimum-holding-invariants.md) 409 `DELETE_CONFIRMATION_REQUIRED`와 `error.impact` · [BD-11](../backend/decisions/BD-11-duplicate-record-idempotent.md) 중복 추가를 오류로 보지 않는 이유

## API 변경 검증

요청·응답 계약을 바꾸면 정상 요청과 validation 실패를 테스트하고, 관련 API 문서를 갱신합니다. 인증이 포함된 API는 [CONTRIBUTING.md](../../CONTRIBUTING.md)의 인증 변경 검증도 함께 만족해야 합니다.
