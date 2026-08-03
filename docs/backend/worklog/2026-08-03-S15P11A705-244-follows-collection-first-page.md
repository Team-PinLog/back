# 팔로우 목록에 책장별 Collection 첫 페이지 동봉

- **날짜**: 2026-08-03
- **추적**: S15P11A705-244
- **관련**: [docs#39](https://github.com/Team-PinLog/docs/pull/39) · [docs#38](https://github.com/Team-PinLog/docs/issues/38)

내 책장 화면이 팔로우 책장 표지를 그리려면 `GET /follows` + 책장마다 `GET /follows/{id}/collections`로
`1 + N`회가 들었다. `GET /follows`에 `collectionSize` 파라미터를 더해, 주면 각 항목에 그 책장
Collection의 첫 페이지(`collections` — `items`·`nextCursor`·`hasNext`)를 실어 보낸다. 명세 개정은
docs#39, 프론트 확인 완료.

판단들:

- **파라미터가 없으면 DTO 자체가 다르다.** `collections` 필드를 `@JsonInclude`로 숨기는 대신
  `FollowResponse`/`FollowWithCollectionsResponse`로 갈랐다 — "필드 없음"이 직렬화 옵션이 아니라
  타입으로 보장된다. 컨트롤러 반환은 `CursorPage<?>`.
- **책장별 첫 페이지는 창 함수 1회.** `row_number() OVER (PARTITION BY member_id ...)`로 페이지
  전체를 `collectionSize + 1`행씩 끊어 오는 `CollectionFirstPageRepository`를 새로 뒀다(native라
  JPA 인터페이스가 아니라 `NamedParameterJdbcTemplate` — `ContextKeywordRepository`와 같은 선택).
  정렬·노출 조건은 9.3 질의와 같아야 커서를 그쪽이 그대로 이어받는다. 회귀는 코드 읽기가 아니라
  `SqlQueryCounter` 측정으로 막는다(`FollowListCollectionsQueryCountTests`).
- **작성자 탈퇴 필터를 집계 질의에 두지 않았다.** 팔로우 목록 질의가 탈퇴 회원을 이미 거르고,
  집계는 그 결과 위에서만 돈다.
- **`collectionSize` 보정은 `size`와 같은 `CursorPage.normalizeSize` 하나다** — 서버 방어 상한의
  답은 하나(S15P11A705-117). 명세에도 보정과 권장 호출값(`size=10`·`collectionSize=5`)을 명시했다.
  `size` 기본은 1.4의 20 그대로이며 `collectionSize`가 있어도 달라지지 않는다.
- **안쪽 커서는 마지막으로 실린 항목을 가리킨다.** 초과 행(probe)을 가리키면 그 행이 건너뛰어진다.
  기존 9.3 엔드포인트로 이어받는 연속성은 테스트로 고정했다.
