# BI-36. 조회 응답 3곳에 Record·Collection 키워드 노출

- **상태**: ✅ 완료
- **날짜**: 2026-08-01
- **관련**: S15P11A705-240, [back#145](https://github.com/Team-PinLog/back/issues/145),
  [BD-18](../decisions/BD-18-keyword-preset-and-visibility.md)(원본은 Context Keyword·상위는 읽기 집계),
  [BI-35](BI-35-2026-07-31-shelf-browse.md)(빈 배열을 "남긴 것"으로 기록한 곳)

## 산출

- `ContextKeywordRepository`에 공개용 집계 둘 신설 — `findKeywordsPublic`(Record 단위)·`findCollectionKeywordsPublic`(Collection 단위). 둘 다 `PUBLIC` 화이트리스트가 WHERE 절에 있고, 페이지 전체를 `IN (...)` 한 번으로 모은다.
- 소유자 조회(§5.2 상세·§5.3 장소 조회·§7.3 소유자 Record 페이지)가 기존 `findKeywordsForOwner`로 채워진다 — `PUBLIC + PRIVATE_ONLY`.
- 타인 조회(§7.3 카드·§9.3·§8.1 목록)가 신설 공개용 집계로 채워진다 — `PUBLIC`만.
- `KeywordExposureApiTests` 8개 — Visibility 경계·생성 응답 가드·Record 제거 반영·페이지 내 그룹핑.

## 이 티켓이 채운 공백

키워드를 실제로 채우는 응답이 AI 자연어 검색 하나뿐이었다. `RecordDetailResponse`·`PublicRecordCardResponse`·`FollowedCollectionResponse`가 전부 `List.of()` 고정이라, AI 처리가 끝난 뒤에도 영구히 빈 배열이 나갔다. javadoc의 "AI 파트가 채우기 전까지 빈 배열이 정상"이 사실상 "언제까지나 빈 배열"이었다.

## 설계 판단

### 소유자와 타인의 Visibility가 다르다 — 티켓 완료 조건을 하나 정정했다

티켓 초안은 "세 곳 모두 `PUBLIC`만"이었는데 04 §2 표는 `PRIVATE_ONLY`를 **본인 공개**로 정의한다. 소유자 상세가 `PUBLIC`만 보여주면 AI 검색(소유자 전용, `PUBLIC + PRIVATE_ONLY`)과 같은 Record가 다른 키워드를 갖는다. 그래서 소유자 경로는 기존 `ForOwner` 범위를 그대로 쓰고, **타인 경로만 `PUBLIC` 화이트리스트**다. 범위가 메서드 단위로 갈려 있어(`ForOwner` / `Public`) 호출부가 조건을 고를 여지가 없다 — BD-13의 "실수가 유출이 아니라 다른 형태로 실패하게"와 같은 원리다.

### 생성 응답은 계속 빈 배열이다 — 자연히 비는 게 아니라 명시적으로 비운다

`POST /records`가 `CONTEXT_ADDED`로 떨어지면 기존 Record에 이미 완료된 키워드가 있을 수 있다. 조립을 `detailOf`에 맡겼으면 생성 응답에 그 키워드가 실렸을 것이다. 명세 1.3의 "생성 직후 빈 배열" 계약을 지키려고 생성 경로는 호출부에서 `List.of()`를 명시적으로 넘긴다 — 가드 테스트(`recordCreateResponseKeepsKeywordsEmpty`)가 완료된 키워드를 미리 만들어 두고 생성 응답이 그래도 비어 있음을 고정한다.

### Feed의 집계와 합치지 않았다

`FeedKeywordRepository`와 겹쳐 보이지만 반환 계약이 다르다 — 그쪽은 점수 계산용 `code`·가중치 **분포**(AI 파트 소유 계약)이고, 이쪽은 화면 표시용 `display_name` **목록**이다. 합치면 한쪽 변경이 다른 쪽을 조용히 깨고, AI 파트 소유 코드에 백엔드 화면 요구가 스며든다. Feed가 `code`를 내보내는 계약 위반은 별건으로 남아 있다([#146](https://github.com/Team-PinLog/back/issues/146), AI 파트) — 이 티켓의 집계는 처음부터 `display_name`이라 그쪽 진행과 무관하게 완결된다.

### Collection 단위 SQL의 `r.deleted_at` 조건은 방어다

Record 삭제가 Context·링크를 연쇄 소프트 삭제하므로 보통은 `ct.deleted_at`·`cr.deleted_at`에 이미 걸린다. 연쇄가 한 곳이라도 어긋난 데이터에서 삭제된 Record의 Keyword가 살아나면 안 되므로 join 조건으로 한 겹 더 둔다 — `FeedCandidateRepository`가 같은 원리로 모든 노출 조건을 WHERE에 둔다.

## 검증

운영 AI 없이 검증했다 — FastAPI가 채울 자리(`ai.context_keyword`)를 테스트가 직접 넣고 `keyword_status`를 `COMPLETED`로 올린다. Context 생성이 상태 행을 `PENDING`으로 넣어 두므로(BD-36) UPDATE만 하면 된다.

쿼리 수 일정(N+1 없음)은 단언하지 못했다 — 쿼리 카운터 인프라가 없다(feed-tests N1~N7도 같은 이유로 미결이다). 대신 집계가 페이지 전체를 한 번에 받는 구조를 SQL 자체(`IN (:ids)`)와 호출부(페이지당 1회 호출)로 보장하고, 페이지 내 그룹핑이 맞는지를 테스트(`keywordsAreGroupedPerCollection`)로 고정했다.

## 남긴 것

- **`RecordByPlaceResponse`(§5.3)도 채워진다.** 티켓이 명시한 3곳 밖이지만 같은 `RecordDetailResponse`를 내보내는 소유자 조회라, 채우지 않으면 같은 DTO가 경로에 따라 다른 동작을 갖는다.
- **쿼리 카운터.** 도입하면 이 티켓의 "질의 수 일정"과 feed-tests N1~N7을 함께 고정할 수 있다.
