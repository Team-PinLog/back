# 탐색 탭에서 이미 팔로우한 회원의 Collection을 제외했다

- **날짜**: 2026-08-07
- **관련**: [BD-51](../decisions/BD-51-explore-excludes-followed-members.md) · AI 파트 명세 [feed-scoring](../../ai/spec/feed-scoring.md) 2.1·3.1 · [feed-recommendation](../../ai/spec/feed-recommendation.md) 3.2

사용자 요청으로 탐색(`GET /v1/feed/collections`) 탭에서 이미 팔로우 중인 회원의 책이 뜨지 않도록 했다. 조사해 보니 현재 코드는 버그가 아니라 AI 파트 소유 명세(`feed-scoring.md`)가 "팔로우 = 사용자가 명시적으로 표현한 유일한 관심 신호"라는 근거로 가장 큰 가중치(`w_follow = 0.500`)를 주고 우선 노출하도록 설계한 그대로였다. 요청한 동작은 이 설계를 정면으로 뒤집는다.

`CLAUDE.md` 9번 규칙(코드-문서 충돌은 정본을 임의로 고치지 않고 마킹만 남긴다)의 정신을 따라, AI 파트 소유 값(`FeedProperties`의 `wFollow`·`followLimit`, `FeedCandidateRepository`의 `FOLLOWED_SQL`·`findFollowed`)은 코드에서 지우지 않고 명세 쪽에 `[BD-51 상충]` 마킹만 남겼다. 대신 `FeedService.collectCandidates`가 `findFollowed` 채널 호출을 멈추고, 남은 최신·무작위 채널(`RECENT_SQL`·`SAMPLE_FROM_PIVOT_SQL`·`SAMPLE_WRAPPED_SQL`)의 WHERE 절에 `core.follow` 기준 `NOT EXISTS`를 추가해 팔로우한 회원의 Collection을 직접 제외했다. 그 결과 `followSignal`은 어떤 후보도 팔로우 채널에서 나올 수 없어 항상 0이 된다 — 점수 공식 자체는 건드리지 않고 입력을 막는 방식이라, 명세를 되돌릴 때도 코드를 되돌리기 쉽다. 실패 테스트를 먼저 추가했다(`FeedCandidateChannelTests.exploreChannelsExcludeFollowedMembersCollections`, C10) — RED에서 최신 채널만 새기는 것을 확인하고 SQL을 고쳐 GREEN으로 만들었다. `FeedChannelPlanTests`로 `NOT EXISTS` 추가 후에도 `RECENT_SQL`이 `ix_collection_feed`의 정렬 순서(Presorted Key)를 그대로 쓰는지 확인했다 — Postgres가 Nested Loop Anti Join으로 처리해 순서를 잃지 않았다.

과정에서 두 가지 무관한 선결함을 발견해 함께 고쳤다. (1) `FeedProfileServiceTests`가 존재하지 않는 `PostgresContainerSupport`를 참조해 전체 테스트 컴파일이 깨져 있었다 — `IntegrationContainerSupport`로 교체했다. (2) 같은 파일의 `insertMember`/`insertFollow`/`insertRecordWithContext`가 `deleted ? Instant.now() : null`을 그대로 JDBC 파라미터로 넘겨 `deleted=true` 분기에서 `BadSqlGrammarException`("Can't infer the SQL type … java.time.Instant")이 났다 — `java.sql.Timestamp.from(...)`으로 바꿨다. 둘 다 이번 컴파일이 처음으로 이 파일을 통과시키면서 드러난, 전부터 있던 문제다.

부수적으로 `FeedKeywordDisplayOrderTests`가 "방금 만든 Collection을 팔로우해 점수를 강제로 올려 페이지 밖으로 밀리지 않게 하는" 픽스처 트릭을 쓰고 있었는데, 팔로우가 이제 오히려 제외 조건이라 이 트릭이 역효과를 냈다(8개 테스트가 RED로 변함). 팔로우 대신 커서를 끝까지 순회해 대상 Collection을 찾는 방식(`findAcrossPages`)으로 바꿨다 — 이 테스트들은 순위가 아니라 선택된 Keyword의 정렬만 검증하므로 몇 페이지째에 있는지는 상관없다.

`./gradlew clean check --no-daemon`으로 전체 검증했다.
