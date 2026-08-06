# Feed keywords를 code에서 display_name으로 교체 (S15P11A705-252)

- **상태**: ✅ 완료
- **관련**: back#146(정본) · back#145(선행 조건, CLOSED) · S15P11A705-252

## 무엇을 만들었나

`GET /v1/feed/collections` 응답의 `keywords`가 `keyword_preset.code`(`WALK`·`COFFEE_CHAT`)를
내보내던 것을, 08 §6.1이 요구하는 `display_name`(`산책`·`카페`)으로 교체했다. 같은 규칙을 쓰는
Record 상세·생성·AI 검색·(back#145로 채워진) Collection 목록은 이미 `display_name`을 냈고 Feed
하나만 어긋나 있었다.

## 핵심 결정 — 점수 계산의 키는 `code`로 유지한다

`FeedScorer.weightedJaccard`가 Collection 특징과 사용자 Profile을 비교할 때 쓰는 키는 여전히
`keyword_preset.code`다. **`display_name`으로 바꾸지 않았다.**

이유: 양쪽 키가 같기만 하면 Jaccard는 성립하므로 `display_name`을 키로 써도 **당장은 정상 동작한다.**
그런데 `display_name`은 표시용 라벨이라 바뀔 수 있다. 바뀌는 순간 그 이전에 계산돼 저장된 값과
매칭이 조용히 어긋난다. 예외가 나지 않고 테스트도 깨지지 않는다. 증상은 "추천 품질이 이유 없이
나빠짐"으로만 나타나 원인 추적이 사실상 불가능해진다. `code`는 `ai/data/keyword_preset.yaml`의
불변 식별자이므로 이 위험이 없다.

```text
findPublicKeywordWeights · findProfile   →  code를 키로 유지 (변경 없음)
응답 조립(FeedService.rank/keywordsOf)     →  code → display_name 매핑을 마지막에 한 번 적용
```

`FeedKeywordDisplayNameTests.renamingAPresetDoesNotMoveTheScoringKey()`가 이 결정을 실행으로
고정한다. Preset의 `display_name`을 바꾼 뒤에도 두 집계 메서드의 반환 키가 그대로임을 단언한다.

## 변경한 파일

| 파일 | 무엇을·왜 |
|---|---|
| [`FeedKeywordRepository`](../../../src/main/java/com/pinlog/pinlogback/domain/feed/repository/FeedKeywordRepository.java) | `findPublicDisplayNames(Collection<String> codes)` 신설. `code IN (:codes)` 한 번으로 표시값을 모은다. 가시성 화이트리스트(`PUBLIC` + `is_active`)를 특징 집계 쿼리와 동일하게 둬서 표시값 조회 자체가 두 번째 방어선이 되게 했다 |
| [`FeedService`](../../../src/main/java/com/pinlog/pinlogback/domain/feed/service/FeedService.java) | `rank()`가 특징 집계 뒤 `displayNamesOf(keywords)`로 한 번 더 조회해 `RankedFeed`에 싣는다. `keywordsOf(card)`가 `code` 집합을 표시값으로 변환하며, **표시값을 못 찾은 code는 버린다**(아래 참고) |
| [`FeedCollectionItemResponse`](../../../src/main/java/com/pinlog/pinlogback/domain/feed/dto/FeedCollectionItemResponse.java) | javadoc의 "공개 가능한 `PUBLIC` Keyword **code**"를 "**display_name**이며 `code`는 노출하지 않는다"로 정정. 코드와 문서가 같은 방향으로 어긋나 있던 것(back#146)을 바로잡았다 |

## 표시값을 못 찾으면 — code로 대신 채우지 않고 버린다

특징 집계와 표시값 조회는 별개의 쿼리다. 그 사이에 Preset이 폐기(`is_active=false`)되거나 차단
(`visibility='BLOCKED'`)되면, 특징 집계가 이미 들고 온 `code`를 표시값 쪽에서 못 찾는 상태가
생긴다. 이때 `code`로 대신 채우는 폴백을 두면 그 폴백 자체가 08 §6.1 위반이 된다. 그래서 그런
`code`는 응답에서 조용히 빠진다. "영문이 뜬다"가 아니라 "그 Keyword만 덜 뜬다"가 계약이다.
`FeedServiceTests.aCodeWithoutAResolvableDisplayNameIsDroppedRatherThanShownAsCode()`로 고정했다.

## N+1 — 측정으로 확인했다

"안 만든다"는 코드를 읽고 판단할 수 있는 주장이 아니라고 보고, `SqlQueryCounter`
([src/test/.../support/SqlQueryCounter.java](../../../src/test/java/com/pinlog/pinlogback/support/SqlQueryCounter.java))를
새로 만들어 실제 SQL 왕복 횟수를 셌다. `DataSource`를 프록시로 감싸 `Connection.prepareStatement`
호출을 가로채는 방식이라 JdbcTemplate·Hibernate 어느 경로든 걸린다.

`FeedKeywordQueryCountTests.theDisplayNameLookupStaysOneQueryAsCandidatesAndKeywordsGrow()`가
Collection 3건에서 12건으로 늘려도 표시값 조회 쿼리 수가 그대로(1회)임을 단언한다. 페이지 전체의
`code`를 모아 한 번에 조회하는 구조라 프리셋이 27개뿐이라는 사실과 무관하게 후보 수가 늘어도
쿼리 수는 늘지 않는다.

같은 카운터로 `feed-tests.md` N4·N5(Collection 특징 집계가 `IN (...)` 한 번)도 함께 고정했다.
명세가 "쿼리 카운터로 검증한다"고 적어 두고도 카운터가 없어 미검증 상태였던 것을 이번에 채웠다.

## 테스트

- `FeedKeywordDisplayNameTests` — 응답이 `display_name`이고 `code`가 어디에도 없음, 표시값 변경이
  점수 계산 키에 영향 없음, 표시값 조회도 동일한 가시성 화이트리스트를 씀
- `FeedKeywordVisibilityTests.onlyPublicActiveKeywordsCrossTheBoundary` — 기존 P3~P7에 "응답에
  `code`가 전혀 없다" 단언 추가
- `FeedServiceTests` — 응답 keywords가 표시값 기준으로 정렬됨, 표시값 미해결 code는 버려짐,
  표시값 조회가 요청당 1회(Mockito `verify(times(1))`)
- `FeedKeywordQueryCountTests` — 표시값 조회·Collection 특징 집계 모두 쿼리 카운터로 N+1 부재 확인
- `./gradlew clean check --no-daemon` 통과 (Testcontainers, 로컬 Docker)

## 후속 — 다루지 않은 것

- 프론트가 기존 영문 `code`를 화면에 그대로 쓰고 있었는지는 back 레포 범위 밖이라 확인하지
  못했다. 결과 패킷에 조사 필요로 남긴다.
- `docs/ai/troubleshooting/README.md`·`implements/README.md`의 인덱스가 back#154에서 이미
  "원본과 어긋난 사례"로 지적된 적이 있다. 이번 항목은 색인에 새로 추가했지만, 그 전 항목들의
  정합성까지 다시 검증하지는 않았다. 범위 밖이기 때문이다.
