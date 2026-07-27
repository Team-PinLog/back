# BI-04. 커서 기반 목록 응답 공용 타입(`Cursor`, `CursorPage`) 구현

- **상태**: ✅ 완료
- **날짜**: 2026-07-27
- **관련**: S15P11A705-42 (`377d006`, `14888f2`, `72b2899`), [BD-04](../decisions/BD-04-cursor-pagination.md)

## 산출

- `src/main/java/com/pinlog/pinlogback/global/response/Cursor.java` — `record Cursor(String sortKey, long id)`. `encode(String, long)`은 `sortKey + "," + id`를 만들어 URL-safe·패딩 없는 Base64(`Base64.getUrlEncoder().withoutPadding()`, `StandardCharsets.UTF_8`)로 인코딩한다. `encode(Instant, long)`은 `Instant.toString()`(ISO-8601 UTC)을 정렬키로 쓰는 오버로드, 인스턴스 메서드 `encode()`는 자기 자신을 다시 인코딩한다. `decode(String)`은 같은 Base64·인코딩으로 역연산한 뒤 **마지막** `,`를 구분자로 잘라 `sortKey`/`id`를 복원한다. Base64 디코딩 실패, 구분자 없음, id가 숫자가 아님 세 경우 모두 `InvalidCursorException extends BusinessException(ErrorCode.INVALID_INPUT)`을 던져 `GlobalExceptionHandler`를 거쳐 `400`으로 응답한다 — 원본 커서 문자열은 예외 메시지에 담기지 않는다.
- `src/main/java/com/pinlog/pinlogback/global/response/CursorPage.java` — `record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext)`. 정적 팩터리 `of(items, nextCursor)`(`hasNext = nextCursor != null`), `last(items)`(`nextCursor = null`, `hasNext = false`), `empty()`. 캐노니컬 생성자에서 `items = List.copyOf(items)`로 방어적 복사. `public static final int DEFAULT_SIZE = 20`, `MAX_SIZE = 100`, `static int normalizeSize(Integer requested)`(`null`·0·음수 → `DEFAULT_SIZE`, `MAX_SIZE` 초과 → `MAX_SIZE`, 그 사이 값은 그대로). **`@JsonInclude`를 붙이지 않았다** — `ApiResponse`와 달리 마지막 페이지에서도 `nextCursor` 키 자체가 사라지면 안 되기 때문이다(아래 "반복될 함정" 참고).

컨트롤러·서비스·리포지토리는 이번 범위에 없다 — 공용 타입만 추가했다. 실제 도메인 목록 API가 이 타입을 쓰는 시점은 그 도메인 티켓에서 다룬다.

## 검증

실제 실행 결과는 아래 "게이트 실행" 절 참고. 테스트별 확인 내용은 다음과 같다.

- `CursorTest`(`global/response`) — `encodesSortKeyAndIdAsBase64`: `encode("2026-07-23T10:00:00Z", 8801L)`을 직접 Base64 디코딩해 `"2026-07-23T10:00:00Z,8801"`과 일치함을 확인. `encodesInstantSortKeyAsIsoUtc`: `Instant` 오버로드가 `Instant.toString()`(ISO-8601 UTC)을 그대로 정렬키로 쓰는지 확인. `decodeRestoresSortKeyAndId`: encode→decode 왕복이 원본 `sortKey`/`id`를 복원하는지 확인. `decodeRejectsNonBase64`/`decodeRejectsMissingSeparator`/`decodeRejectsNonNumericId`: 세 가지 손상된 입력이 모두 `InvalidCursorException`을 던지는지 확인. `decodeSplitsOnLastCommaSoSortKeyMayContainCommas`: 정렬키 자체에 `,`가 두 개 들어간 값(`"a,b,2026-07-23T10:00:00Z"`)을 인코딩·디코딩해도 정렬키 전체가 그대로 복원되는지 확인 — 이 테스트는 뮤테이션 검사(구분자를 `indexOf`(첫 `,`)로 바꾸면 실패)로 실제로 마지막 `,` 기준 분할을 강제하는지 별도로 확인했다.
- `CursorPageTest`(`global/response`) — `ofWithNextCursorHasNext`/`lastPageHasNoNextCursor`/`emptyPageHasNoItemsAndNoNext`: 세 팩터리의 필드 값을 확인. `normalizeSizeUsesDefaultWhenAbsent`/`normalizeSizeCapsAtMax`/`normalizeSizeRejectsNonPositiveByFallingBackToDefault`: `null`·상한 초과·0·음수 네 경계값이 각각 기본값(20) 또는 상한(100)으로 보정되는지 확인. `serializesWithItemsNextCursorHasNext`: 운영 Jackson 3 매퍼(`tools.jackson.databind.json.JsonMapper`)로 직렬화한 JSON 문자열에 `items`·`nextCursor`·`hasNext` 세 키가 모두 값과 함께 나타나는지 확인. `lastPageSerializesNextCursorAsNull`: `CursorPage.last(...)`를 직렬화한 JSON에 `"nextCursor":null`이 **키와 함께** 나타나는지 확인 — `@JsonInclude(NON_NULL)`을 붙였다면 이 테스트가 실패했을 것이다.
- 기존 전체 회귀(`DeploymentContractTests`·`OpenApiDocsTests`·member·envelope 관련 테스트) — 이번 태스크는 신규 공용 타입만 추가했고 기존 클래스를 수정하지 않았으므로 그대로 통과.

## 반복될 함정 (다음 사람에게)

1. **`Cursor.decode`는 마지막 `,`를 구분자로 쓴다.** 정렬키(예: 향후 도메인이 자유 텍스트나 복합 키를 정렬키로 쓰는 경우)에 `,`가 포함될 수 있다는 전제로 설계했다 — `sortKey.lastIndexOf(SEPARATOR)`가 아니라 `decoded.lastIndexOf(SEPARATOR)`(디코딩된 전체 문자열 기준)로 나눈다. 구분자 처리를 고치거나 정렬키 형식을 바꿀 때 첫 `,` 기준으로 되돌리면 정렬키에 `,`가 들어가는 순간 조용히 잘못된 위치에서 분리되어 `id` 파싱이 깨지거나(숫자가 아닌 조각이 `id`로 들어가 `NumberFormatException` → `InvalidCursorException`), 더 나쁘게는 정렬키 뒷부분이 잘려나간 채로 다음 페이지 조회 조건에 쓰인다. `CursorTest.decodeSplitsOnLastCommaSoSortKeyMayContainCommas`가 이 동작을 뮤테이션 검사로 고정하고 있으니, 구분자 로직을 바꿀 때는 이 테스트가 실제로 실패하는지부터 확인한다.
2. **`CursorPage`에 `@JsonInclude`를 붙이면 안 된다.** `ApiResponse`(BD-03/BI-03)는 `@JsonInclude(NON_NULL)`로 `data`/`error` 중 없는 쪽 키를 생략하지만, `CursorPage`는 의도적으로 그 관례를 따르지 않는다. 마지막 페이지에서 `nextCursor`가 `null`인 것과 "이 응답에 `nextCursor` 필드가 아예 없는 것"은 클라이언트 입장에서 다른 의미로 읽힐 수 있어(키 부재를 "필드를 아직 모른다"로, 명시적 `null`을 "더 없다"로 구분해야 할 수 있음), 명세 §1.4가 마지막 페이지에서도 `nextCursor: null`을 명시적으로 보여준다. 여기에 `@JsonInclude(NON_NULL)`이나 `@JsonInclude(Include.NON_NULL)`을 습관적으로 붙이면 `lastPageSerializesNextCursorAsNull` 테스트가 실패한다(키 자체가 사라짐). 이 클래스에는 다른 공용 응답 타입의 `@JsonInclude` 관례를 그대로 복사하지 않는다.

## 게이트 실행

```
./gradlew clean check --no-daemon
```

Docker Desktop 실행 상태에서 PostgreSQL/pgvector Testcontainers를 포함해 전체 스위트를 실행했다. 실제 tail은 작업 리포트(`task-3-report.md`)에 그대로 붙였다 — `BUILD SUCCESSFUL`, 신규 `CursorTest`(7)·`CursorPageTest`(8) 포함 테스트 실패 없음, `DeploymentContractTests`·`OpenApiDocsTests` 포함 기존 전체 회귀 통과, Checkstyle(main·test) 통과.
