# BI-34. 작성자 공개 책장 탐색

- **상태**: ✅ 완료
- **날짜**: 2026-07-31
- **관련**: S15P11A705-206, [back#85](https://github.com/Team-PinLog/back/issues/85),
  [BD-43](../decisions/BD-43-shelf-in-follow-domain-under-collections-path.md)(도메인·경로 결정),
  [BI-30](BI-30-2026-07-31-me-summary.md)(미구현 둘 중 나머지 하나를 채운 곳)

## 산출

- `GET /api/core/v1/feed/collections/{collectionId}/shelf` — `ShelfResponse`(`sourceCollectionId`·`follow`·`collections` 3층).
- `ShelfController`·`ShelfService` — `domain/follow` 아래 신설. 경로 접두어는 `/v1/collections`다([BD-43](../decisions/BD-43-shelf-in-follow-domain-under-collections-path.md)).
- `ShelfResponse`·`ShelfFollowState` — 응답 DTO 둘.
- `ShelfApiTests` — 통합 테스트 13개.

**새 질의도 새 리포지토리 메서드도 만들지 않았다.** 기존 `CollectionRepository.findPublishedFirstPageByMemberId`·`findPublishedPageByMemberIdAfter`, `FollowRepository.findByFolloweeMemberIdAndFollowerMemberId`, `MemberRepository.isActive`로 전부 조립된다.

## 이 티켓이 채운 공백

[BI-30](BI-30-2026-07-31-me-summary.md)이 "명세 대비 미구현은 둘뿐"이라고 적었고, 그 둘 중 나머지가 이것이다. 명세 §13.6의 탐색 흐름에서 가운데 한 칸이 비어 있었다.

```
GET  /feed/collections            ✅ (S15P11A705-120)
→ GET  /collections/{id}          ✅
→ GET  /feed/collections/{id}/shelf  ← 이번
→ POST /follows { collectionId }  ✅
```

`POST /follows`가 `collectionId`만 받으므로 팔로우 자체는 막히지 않았다. 막혀 있던 것은 **팔로우하기 전에 그 작성자의 다른 책장을 둘러보는 것**이고, 06 §5.3이 식별자 은닉의 진입점으로 Collection id를 고른 근거가 그것이다.

## §9.3과 같은 데이터, 다른 진입 키

`GET /follows/{followId}/collections`(§9.3)와 조회 대상이 같다 — 어떤 작성자의 발행 Collection 커서 목록이다. 다른 것은 셋뿐이다.

| | §9.3 | §8.1 (이번) |
|---|---|---|
| 진입 키 | `followId` — 이미 관계가 있다 | `collectionId` — 관계가 없다 |
| 응답 | `CursorPage` 하나 | `sourceCollectionId`·`follow`·`collections` 3층 |
| 작성자 탈퇴 | 빈 목록(BI-11) | **404** |

마지막 줄이 갈리는 이유는 탈퇴가 각각 다른 것을 가리키기 때문이다. §9.3에서는 내 Follow 목록의 한 항목이 사라진 것이라 빈 목록이 맞고, §8.1에서는 **진입점 자체가 공개 대상이 아닌 것**이라 존재를 숨겨야 한다. 진입점 판정을 `FollowService.follow`(§8.2)와 똑같이 둔 것도 같은 이유다 — 두 Endpoint가 같은 진입점을 공유하므로 한쪽만 느슨하면 그쪽이 유출 지점이 된다.

## 테스트에서 판단이 필요했던 것

### 404 테스트 세 개는 구현 전에 통과한다

매핑이 없으면 Spring이 404를 주고, `GlobalExceptionHandler`가 그것도 `RESOURCE_NOT_FOUND` 봉투로 감싼다(`statusCode.isSameCodeAs(NOT_FOUND)` 분기). 그래서 **응답만으로는 "핸들러 없음"과 "리소스 없음"을 구별할 수 없다.**

RED 단계에서 13개 중 10개가 실패하고 404 테스트 셋이 통과한 것이 이 때문이다. 이 셋은 핸들러가 생긴 뒤에야 의미를 갖는 가드이고, 구현을 이끈 것은 200 경로의 실패(`Status expected:<200> but was:<404>`)다. 커서·필터·팔로우 상태까지 테스트를 먼저 다 쓰고 한 번에 RED를 확인한 뒤 구현했다.

### 작성자 id 유출은 값이 아니라 필드 이름으로 검증한다

응답 본문에서 작성자 id를 문자열로 찾는 방식을 쓰지 않았다. Collection id가 작성자 id의 자릿수를 포함하면(작성자 3, Collection 13) 유출이 없어도 실패해서, 통과·실패가 구현이 아니라 시퀀스 값에 좌우된다.

대신 **필드 이름 집합**을 단언한다 — 3층 각 노드와 항목의 필드가 명세 §8.1과 정확히 일치하는지 본다. 필드가 하나라도 늘면 실패하므로 "작성자 신원이 새로 실리는" 변경을 잡는다.

## 남긴 것

**`keywords`가 항상 빈 배열이다.** `FollowedCollectionResponse.from`이 `List.of()`를 고정으로 넣는다 — §9.3이 원래 그랬고 이번에 그 DTO를 그대로 재사용했다.

그런데 Feed 응답(`FeedCollectionItemResponse`)은 `FeedKeywordRepository`로 실제 `PUBLIC` Keyword를 채운다. 그래서 **탐색 탭에서 키워드가 보이던 Collection을 눌러 작성자 책장으로 들어가면 키워드가 사라진다.** 명세 §8.1 예시에는 `["산책", "카페"]`가 들어 있으니 계약과도 어긋난다.

이번 범위에 넣지 않은 이유는 채우는 수단(`FeedKeywordRepository`)이 AI 파트가 소유하는 Feed 추천 코드이고, §9.3까지 함께 고쳐야 하는 별건이기 때문이다. 별도로 제기한다.
