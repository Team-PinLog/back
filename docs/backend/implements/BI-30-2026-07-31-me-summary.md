# BI-30. 마이페이지 요약 조회

- **상태**: ✅ 완료
- **날짜**: 2026-07-31
- **관련**: S15P11A705-200, [back#125](https://github.com/Team-PinLog/back/issues/125),
  [BI-29](BI-29-2026-07-30-member-withdrawal.md)(`MeController`와 `SocialAccountRepository.findByMemberId`를 만든 곳)

## 산출

- `GET /api/core/v1/me/summary` — `MeSummaryResponse`(provider·email + 카운트 4).
- `MemberSummaryService` — 집계 조회.
- 집계 리포지토리 메서드 4개 — `RecordRepository.countByMemberId`, `CollectionRepository.countByMemberId`, `FollowRepository.countByFolloweeMemberId`·`countByFollowerMemberId`.

## 이 티켓이 채운 공백

명세를 구현과 대조해 보니 **미구현이 둘뿐이었다** — 이것과 `GET /feed/collections/{id}/shelf`([#85](https://github.com/Team-PinLog/back/issues/85)). 나머지 엔드포인트는 전부 있었다.

프론트의 프로필 화면이 요구한 두 기능(내 프로필, 팔로잉·팔로워 수)이 **이 엔드포인트 하나**로 해결된다 — 명세 3.5가 계정 정보와 카운트 넷을 한 응답에 담아 뒀다.

> 함께 요청된 **"내 기록 조회"는 계약에 없다.** 기록 장(§5)의 조회는 지도 마커·상세·장소별 조회 셋뿐이고 목록 엔드포인트가 없다. 필요해지면 `docs` 개정이 선행이다(`CLAUDE.md` 9번). 이번에는 불필요하다는 확인을 받아 범위에서 뺐다.

## 열려 있던 질문에 답이 나왔다 — `@SQLRestriction`은 count에도 적용된다

`MemberRepository.isActive`의 javadoc이 이 질문을 별건으로 남겨 뒀다.

> `existsById`가 아니라 `findById`를 쓰는 것은 기존 세 경로와 같은 조회를 유지하려는 것이다. 바꾸면 `@SQLRestriction`이 count 쿼리에도 적용되는지가 **별개의 질문**이 되고, 그것은 중복 제거인 이 변경의 범위가 아니다(S15P11A705-147).

활성 기준 집계가 넷 필요해지면서 이 티켓에서 처음 답이 필요해졌다. **적용된다** — 파생 `countBy...`만으로 성립하고 `@Query`가 필요하지 않다.

**뮤테이션으로 확인했다.** `countByMemberId`를 조건 없는 native 쿼리로 바꿨을 때:

```java
@Query(value = "SELECT count(*) FROM core.record WHERE member_id = :memberId", nativeQuery = true)
```

```
마이페이지 요약 > 소프트 삭제된 Record·Collection은 카운트에서 빠진다  FAILED
8 tests completed, 1 failed
```

그 1건만 실패한다. 즉 이 성질을 지키는 단언이 실제로 있다. **조건을 명시하려다 빠뜨리는 쪽이 오히려 위험**하다는 것도 같은 실험이 보여 준다.

결론을 두 곳에 적었다 — `RecordRepository.countByMemberId`의 javadoc과, 원래 질문을 남긴 `MemberRepository.isActive`의 javadoc이다. 후자에는 "이제 `existsById`로 바꾸는 것을 막을 이유는 없지만 바꿀 이유도 없다"까지 남겼다.

## 설계 판단 셋

**① 카운트 쿼리를 넷으로 나눴다.** 한 번의 조인으로 묶으면 **카운트가 곱해진다** — Record 3건과 Collection 2건을 조인하면 6이 된다. `count(DISTINCT ...)`로 피할 수는 있으나, 화면 진입당 1회 호출되는 경로에서 PK·FK 인덱스를 타는 count 넷이 그 복잡도를 살 이유가 되지 않는다.

**② 팔로워 수에 `DISTINCT`가 필요하지 않다.** 유니크가 `(followee_member_id, follower_member_id)` 활성 기준이라(`V3__core_domain.sql:125`) 한 사람이 그 작성자의 Collection을 여러 개 팔로우해도 **행이 하나다.** 팔로우 단위가 Collection이 아니라 작성자라는 뜻이고, 두 번째 시도는 `409 DUPLICATE_FOLLOW`로 거절된다. 그 성질을 테스트로 고정했다 — 계약(3.5)이 "팔로워 수"라고만 적어 단위가 드러나지 않기 때문이다.

**③ 소셜 계정이 없으면 `IllegalStateException`이다.** 가입이 회원과 소셜 계정을 한 트랜잭션에서 만들므로(`SocialLoginService.signUp`) 정상 상태에서는 발생하지 않는다. 조용히 빈 값을 내보내면 *"`email`은 항상 있다"*는 계약(3.5, `06 §2.2`)이 깨진 채 프론트로 나간다 — 프론트가 값 없음 대비를 하지 않기로 한 근거가 그 문장이다.

## 드러난 것

**픽스처 계산을 틀렸고 테스트가 잡았다.** 첫 실행에서 `collectionCount`를 3으로 기대했는데 2였다 — 세 번째 Collection은 다른 회원 소유라 내 카운트에 들어가지 않는다. 구현이 아니라 기대값이 틀린 경우였고, 그 자리에서 픽스처에 주석으로 소유자를 명시했다.

**소프트 삭제를 삭제 API가 아니라 `JdbcTemplate`으로 만들었다.** 삭제 API는 연쇄 삭제까지 수행해 세려는 대상이 함께 사라진다. 그러면 검증하는 것이 "카운트가 활성만 세는가"에서 "연쇄 삭제가 무엇을 지웠는가"로 옮겨간다.

## 감수하는 것

- **응답에 `provider` 하나만 담는다.** 계정당 소셜 계정이 하나인 것은 계정 연결 흐름이 없기 때문이고(`SocialLoginService.login`이 미등록 계정을 **새 회원**으로 만든다), 그 전제가 타입에는 없다. `findByMemberId`가 목록을 반환하므로 첫 행을 쓴다 — 연결 기능이 생기면 여기가 먼저 깨진다.
- **집계가 실시간 count다.** 캐시·비정규화가 없어 회원 데이터가 커지면 이 경로의 비용도 함께 커진다. 화면 진입당 1회라 지금 규모에서는 문제가 아니다.

## 남은 것

- **`AiDerivedDataInvalidationTests`가 아직 공용 픽스처를 쓰지 않는다.** 아래 리팩터에서 member 도메인 두 클래스만 옮겼고, 그 파일은 **AI 파트 소유**(S15P11A705-124)라 이 PR에서 건드리지 않았다. 같은 `createRecord`·`createCollection`·`firstContextId`가 그쪽에 남아 있으므로, AI 파트가 필요할 때 `CoreApiFixtures`를 상속하면 된다.
- `GET /feed/collections/{id}/shelf`가 여전히 미구현이다([#85](https://github.com/Team-PinLog/back/issues/85)).

## 리팩터 — 공용 픽스처 추출

`createRecord`·`createCollection`·`follow` 같은 픽스처가 클래스마다 복사돼 있었다. `support/CoreApiFixtures`로 올리고 member 도메인 두 테스트가 상속하게 했다 — **두 파일에서 177줄이 사라졌다.**

`support`에 둔 이유는 **도메인을 가로지르기 때문**이다. Record·Collection·Follow를 함께 쓰는 픽스처라 어느 한 도메인 패키지에 두면 그 도메인이 아닌 테스트가 남의 패키지를 참조하게 된다. 한 도메인만 쓰는 픽스처는 계속 그 도메인 안에 둔다 — `FeedFixtures`가 그 경우이고, 이 클래스는 그 구조(추상 클래스 + `IntegrationContainerSupport` 상속 + `protected` 필드)를 그대로 따랐다.

`MemberWithdrawalApiTests`에는 `givenSocialAccount(memberId, providerUserId, email)` 3인자 오버로드를 남겨 공용의 4인자 버전에 위임한다. 그 클래스는 provider를 가리지 않아 Google로 고정되어 있고, 호출부 11곳을 건드리지 않으려는 선택이다.

옮기다 두 번 과하게 지웠다 — 남은 테스트가 쓰는 `Member`와 `Collectors` import를 함께 지워 컴파일이 깨졌고 되돌렸다. 헬퍼를 지울 때 **그 파일의 다른 테스트가 같은 타입을 쓰는지**를 함께 봐야 한다는 것이 드러난 지점이다.

## 검증

`./gradlew clean check --no-daemon` — **417개 통과, 실패 0, 오류 0.** 리팩터 전후 같은 수치다.

신규 8건은 PostgreSQL Testcontainers 기반이고, 카운트 검증 전부에 **소프트 삭제된 행을 함께 두고** 센다. 뮤테이션 1종은 위 "열려 있던 질문" 절에 있다.
