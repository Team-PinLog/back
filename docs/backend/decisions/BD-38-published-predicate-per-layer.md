# BD-38. 발행 여부 판정은 층마다 자체 보유하고, 대가를 테스트로 갚는다

- **상태**: Accepted
- **날짜**: 2026-07-29
- **관련**: Jira 작업([back#93](https://github.com/Team-PinLog/back/pull/93)),
  [back#84](https://github.com/Team-PinLog/back/issues/84),
  [BI-11](../implements/BI-11-2026-07-28-follow-library-api.md),
  [BI-13](../implements/BI-13-2026-07-28-public-scope-filter.md),
  [BD-13](BD-13-public-boundary-query-dto-split.md),
  공용 계약 `Team-PinLog/docs` `static/06_데이터모델_및_무결성.md` §5.2·§5.5·§8

## 맥락

"발행됨"이라는 공개 가시성 판정이 여러 곳에 각자 쓰여 있다. 06 §8이 그 변경을 예고한다.

> `is_published`의 MVP 활용 여부 — 비공개 전환을 제공하면 Feed·타인 Shelf 조회·Collection 상세
> 세 경로에 필터 필요

앞선 Jira 작업은 같은 성격의 중복인 **작성자 미탈퇴** 판정을 `MemberRepository.isActive`
한 곳으로 모았다. 그 티켓은 "세 곳의 코드가 동일해 구현 방식을 정할 것 없다"는 전제가 성립했다.
발행 여부는 그렇지 않아서 방식을 정해야 했다.

### 실제 분포는 아홉 곳이다

back#84는 "일곱 곳"으로 적었으나 틀렸다. Feed를 "후보 채널 3개"로 셌는데, 탐색 채널이 SQL 상수
2개(`pivot` 이상·이하)로 갈려 있고 `VERIFY_SQL`은 후보 채널이 아니라 조립 단계라 빠졌다.

| 층 | 자리 | 표현 |
|---|---|---|
| Java | `CollectionService.getDetailPublic` | `!collection.isPublished()` |
| Java | `FollowService.follow` | `.filter(Collection::isPublished)` |
| JPQL | `CollectionRepository.findPublishedFirstPageByMemberId` | `c.isPublished = true` |
| JPQL | `CollectionRepository.findPublishedPageByMemberIdAfter` | `c.isPublished = true` |
| native SQL | `FeedCandidateRepository.RECENT_SQL` | `c.is_published = true` |
| native SQL | `FeedCandidateRepository.FOLLOWED_SQL` | 같음 |
| native SQL | `FeedCandidateRepository.SAMPLE_FROM_PIVOT_SQL` | 같음 |
| native SQL | `FeedCandidateRepository.SAMPLE_WRAPPED_SQL` | 같음 |
| native SQL | `FeedCandidateRepository.VERIFY_SQL` | 같음 |

### 공유할 하나의 술어가 존재하지 않는다

Feed의 5곳은 네 조건을 한 덩어리로 갖는다.

```sql
c.deleted_at IS NULL
AND c.is_published = true
AND c.record_count > 0      -- Feed에만 있는 규칙
AND m.deleted_at IS NULL
```

core 경로에는 `record_count > 0`이 없다. **빈 Collection은 Feed 후보가 아니지만 상세 조회와
팔로우한 Shelf 목록에는 나온다.** 아홉 곳을 하나의 술어로 묶으면 Feed에 빈 Collection이 후보로
들어오거나 core에서 빈 Collection이 사라진다 — 중복 제거가 아니라 동작 변경이다.

### Java 2곳은 이미 한 곳이다

두 경로 모두 `Collection.isPublished()`를 부른다. `!x`와 `filter(x)`는 판정의 갈림이 아니라 호출
문법의 차이다. Jira 작업의 본문은 "Java 2곳의 판정을 하나로 모은다"고 적었지만 그 전제가
사실과 다르다. 여기서 새 술어를 뽑으면 간접층만 늘어난다.

## 선택지

| 안 | 내용 | 기각 이유 |
|---|---|---|
| (a) 층마다 자체 보유 | 각 층이 판정을 갖고, 근거와 자리 목록을 기록한다 | **택함** |
| (b) SQL 조각 상수 공유 | `@Query` 문자열과 native SQL이 같은 조각을 참조한다 | AI 파트 소유 파일(`domain/feed`)을 고치게 된다. 술어가 하나가 아니라 조각을 "공개 가시성"과 "Feed 후보 자격" 둘로 갈라야 하는데, 그러면 조각이 둘이 되어 애초의 목적(한 곳)이 사라진다. 문자열 공유라 타입 안전성도 없다 |
| (c) 조건 조립 또는 DB 뷰 | `Specification`·QueryDSL로 조립하거나 가시성을 뷰에 넣는다 | QueryDSL·`Specification`이 이 레포에 없어 의존성 결정이 딸려오고, 뷰는 마이그레이션이 딸려온다 |

## 결정

**(a) 층마다 자체 보유한다.** 근거 셋:

1. **비공개 전환이 MVP 범위 밖이다.** 06 §8이 미결로 남긴 것을 이 티켓에서 범위 밖으로 확정했다.
   누락 위험은 실재가 아니라 가설이다.
2. **공유할 하나의 술어가 없다.** Feed는 `record_count > 0`을 포함한 다른 술어다.
3. **층을 넘는 재사용이 불가능하다.** JPQL·native SQL 조건은 Java 술어를 재사용할 수 없다.

실패 응답은 이 결정과 무관하게 호출부가 정한다 — Collection 상세와 팔로우는 404, 팔로우한 Shelf
목록은 제외(빈 목록)다. 규칙이 아니라 표현의 차이라서다(BI-11).

## 감수하는 것과 갚는 방법

대가는 명확하다. **비공개 전환이 들어오면 위 표의 아홉 곳을 찾아야 하고, 하나를 빠뜨리면 비공개로
돌린 Collection이 그 경로에서만 계속 보인다.** 그때 볼 자리가 이 문서와 back#84다.

그 대가를 말로만 감수하지 않는다. 판정 네 곳 중 **셋은 지워도 테스트가 빨개지지 않았다.**

| 경로 | 결정 전 | 결정 후 |
|---|---|---|
| `getDetailPublic` | `PublicCollectionApiTests.unpublishedCollectionIsHiddenFromOthers` | 그대로 |
| `follow` | 없음 | `FollowApiTests.followingUnpublishedShelfIs404` |
| `findPublishedFirstPageByMemberId` | 없음 | `followedShelfCollectionsReturnOnlyPublishedActiveOnes`에 미발행 픽스처 추가 |
| `findPublishedPageByMemberIdAfter` | 없음 | `followedShelfCollectionsArePaginatedByCursor`에 미발행을 **가운데** 배치 |

`followedShelfCollectionsReturnOnlyPublishedActiveOnes`는 이름이 "Published만"을 약속하면서
픽스처는 소프트 삭제된 Collection만 만들고 있었다. 이름이 검증보다 앞서 있었다.

커서 쪽 배치가 가운데인 이유: 첫 페이지 쿼리와 커서 쿼리는 별개 메서드라, 미발행이 최신이면 첫
페이지만 지켜지고 `findPublishedPageByMemberIdAfter`의 조건은 여전히 아무도 보지 않는다.

Feed 5곳은 `FeedCandidateChannelTests.unpublishedCollectionIsExcluded`가 이미 지킨다.

**자체 보유를 택하는 층은 스스로 지켜져야 한다**는 것이 이 결정의 조건이다. 앞으로 이 판정을
가진 자리를 늘릴 때는 그 자리를 지키는 테스트를 함께 만든다.

## 06 §5.2와의 관계

06 §5.2는 "세 경로가 단일 공개 조회 서비스를 거치게 하라"고 한다. back#84가 이미 기록한 대로 이
부분은 따르지 않는다 — 반환 형태가 상세·목록·Feed로 제각각이어서 합치면 잡탕 서비스가 되고 도메인
결합만 늘어난다. 실제 중복은 조립이 아니라 판정이었고, 그 판정을 왜 모으지 않는지가 이 문서다.
공용 문서 쪽 문구 정정은 `Team-PinLog/docs` 소관으로 남는다(CLAUDE.md 9번 규칙 — 우리가 고치지 않는다).
