# BI-20. Feed 추천 MVP — 후보 3채널·결정적 점수·opaque cursor

- **상태**: ✅ 완료 (일부 범위 후속)
- **날짜**: 2026-07-29
- **관련**: Jira 작업, [back#58](https://github.com/Team-PinLog/back/issues/58),
  [BD-34](../decisions/BD-34-feed-deterministic-pagination-without-session-cache.md),
  AI 파트 명세 `docs/ai/spec/feed-*.md`, [P42](../../ai/proposals/P42-feed-mvp-without-place-metadata.md)

정책의 정본은 AI 파트가 소유한 `docs/ai/spec/feed-*.md`다. 이 문서는 **Spring에서 어떻게
구현했고 무엇을 검증했는가**만 다루며 수치를 옮겨 적지 않는다.

## 산출

### 도메인 배치

`domain/feed`에 controller·service·repository·dto·entity를 얹었다(패키지 구조 규약의 `feed` 행).

- `FeedController` — `GET /v1/feed/collections`, `POST /v1/feed/events`. context-path(`/api/core`)는
  매핑에 반복하지 않는다.
- `FeedService` — 파이프라인 조립. **`@Transactional`을 걸지 않는다.** 전 과정이 읽기이고 마지막
  IMPRESSION 기록은 실패해도 응답이 정상이어야 하는데, 한 트랜잭션에 묶으면 이벤트 기록 실패가
  응답까지 되돌린다.
- `FeedScorer`·`FeedRanker` — 순수 계산. 시각은 인자로 받아 밖으로 밀어냈다.
- `FeedProperties`(`@ConfigurationProperties("pinlog.feed")`) — 가중치·채널 배분·탐색 슬롯·
  Cold Start를 전부 설정으로 뺐다. **상수로 박으면 재배포 없이 튜닝할 수 없고, "가중치를 바꿔도
  순위가 안 바뀌는" 회귀를 테스트가 잡을 수 없다.**

### 리포지토리를 JDBC로 둔 이유

`FeedCandidateRepository`·`FeedKeywordRepository`·`FeedEventRepository`는 JPA가 아니라
`NamedParameterJdbcTemplate`이다. 이유가 둘이다.

- 후보 조회는 엔티티 그래프가 필요 없는 id 스캔이고, 표시용 데이터는 최종 재검증 쿼리가 함께 가져온다.
- Keyword 집계는 `ai` 스키마를 크로스 조인하는데, 그 테이블은 FastAPI 소유라 백엔드가 엔티티로
  매핑하지 않는다. `core.feed_event`도 같다 — **마이그레이션 `V102`(AI 구간)가 소유하므로 DDL을
  새로 만들지 않았고 조회·삽입만 한다.** `ix_collection_feed`도 이미 `V3`에 있어 인덱스를 추가할
  일이 없었다.

`ai.keyword_preset.embedding`이 `VECTOR(1536)`인데 Feed가 그 컬럼을 **한 번도 읽지 않는다**는 것이
"요청 경로에서 임베딩을 쓰지 않는다"는 경계의 구조적 증거다.

### 공개 경계

- 응답 DTO(`FeedCollectionItemResponse`)에 **소유자 식별 정보를 담는 필드가 없다.** 조건부
  직렬화나 상속으로 감추지 않고 자리를 없앴다(BD-13·BD-14와 같은 방식). 다양성 조정에 필요한
  `ownerId`는 `FeedCandidate`가 들고 있고 그 타입은 응답으로 변환되지 않는다.
- Keyword 가시성 필터는 **WHERE 절에** 있다. 자바 코드에서 거르면 경로 하나만 빠뜨려도 새어 나간다.
  타인 Collection 특징은 `visibility = 'PUBLIC' AND is_active`, 본인 Profile은
  `visibility IN ('PUBLIC','PRIVATE_ONLY')`.
- 응답의 `keywords`는 **점수 계산에 쓴 것과 같은 Map**에서 꺼낸다. 응답에만 다른 필터가 적용될
  경로가 없다.
- 노출 조건(활성·발행·소유자 미탈퇴·본인 제외·`record_count > 0`)을 세 채널과 최종 재검증 쿼리가
  똑같이 건다.

### 페이지네이션

Redis Session Cache 대신 `requestId`를 무작위 채널의 seed로 삼아 페이지마다 결정적으로
재계산한다. 근거와 감수하는 점은 [BD-34](../decisions/BD-34-feed-deterministic-pagination-without-session-cache.md).

커서는 공통 `Cursor`(`Base64(정렬키,id)`)를 재사용해 정렬키에 `requestId`, id에 offset을 넣는다.
Feed 전용 커서 형식도, Feed 전용 `size` 상한도 만들지 않았다 — 후자는 Jira 작업의
"서버 방어 상한의 답은 하나" 규약이다.

`requestId`는 `data` 안의 **별도 필드**다. 커서에 인코딩하면 클라이언트가 이벤트 보고에 쓸 값을
커서에서 파내야 한다.

### 다양성 조정을 블록 단위로 둔 판단

"한 응답 내 동일 소유자 최대 2건"과 "20개 중 탐색 4건"은 **한 응답**에 대한 규칙이다. 그래서
전체 정렬 결과를 `diversity.page-size` 크기 블록으로 끊고 블록마다 규칙을 적용한다. 전체 목록에
한 번만 적용하면 두 번째 페이지부터 규칙이 사라진다.

소유자 상한은 2단계 통과다 — 상한을 걸고 한 번, 못 채우면 상한을 풀고 한 번. 빈 자리를 남기는
것보다 낫다는 명세 판단을 그대로 옮겼다.

## 검증

`./gradlew clean check --no-daemon` — **292개 통과, 실패 0.** 라인 96.6% · 브랜치 82.2%
(게이트 80%). DB가 필요한 테스트는 전부 PostgreSQL Testcontainers(`pgvector/pgvector:0.8.5-pg16`).

| 계층 | 클래스 | 덮은 명세 항목 |
| --- | --- | --- |
| 단위 | `FeedScorerTests` | S1~S10, Cold Start 가중치 전환, 미래 시각 방어 |
| 단위 | `FeedRankerTests` | D1~D4, 배치 결정성, 동점 안정성, 소유자 상한의 설정 의존 |
| 단위 | `FeedSessionTests` | Q4~Q6 (커서 왕복·opaque·위조 400) |
| 단위(대역) | `FeedServiceTests` | 후보 0건, Profile 실패 폴백(D7), IMPRESSION 실패 무해(E2), E1·E3 |
| 통합 | `FeedCandidateChannelTests` | C1~C6·C8·C9, 재검증 순서 보존과 탈락 |
| 통합 | `FeedKeywordVisibilityTests` | P3~P7, 가중치 정규화, Cold Start 판정 |
| 통합 | `FeedApiTests` | Q1~Q3·Q4·Q7, P1·P2, `keywords: []`(공용 §16 21번), 401 |
| 통합 | `FeedEventApiTests` | E1·E3·E4·E5·E8·E9, 집계 윈도우 |

**후보 0건만 대역으로 검증했다.** 컨테이너를 공유하고 테스트 간 DB를 비우지 않아, 다른 테스트가
만든 Collection이 항상 후보에 들어오므로 통합 테스트로는 이 상태를 만들 수 없다. 같은 이유로
통합 단언은 "응답 전체"가 아니라 **내가 만든 id로 걸러낸 부분**에 건다.

`FeedCandidateChannelTests`는 API가 아니라 리포지토리를 직접 부른다 — 채널 쿼리의 WHERE 절이
검증 대상인데 API까지 거치면 다양성 조정·페이지 크기에 가려 **무엇 때문에 빠졌는지**가 흐려진다.

## 하지 않은 것 (후속)

이 티켓의 필수 범위(후보·점수·API·커서·테스트)는 완결이고, 아래는 계약이 "여유가 있으면"으로
둔 항목 중 남은 것이다.

| 항목 | 상태 | 남은 이유 |
| --- | --- | --- |
| IMPRESSION 별도 스레드 기록 | ❌ | 지금은 응답 직전 동기 기록 + `try/catch`. 실패해도 응답은 정상이라 계약은 지키지만, 명세가 말하는 비동기는 아니다 |
| Redis Profile Cache · Collection 특징 Cache | ❌ | 매 요청 DB 집계. TTL·직렬화·장애 차단이 함께 들어와야 하는 범위라 분리했다 |
| Redis Feed Session Cache | ❌ | BD-34에서 결정적 재계산으로 대체 |
| Cache stale 방어 테스트(K1~K8) | ❌ | Cache가 없으므로 대상이 없다. 최종 재검증 자체는 `verificationPreservesOrderAndDropsIneligible`이 고정한다 |
| N1~N3(외부 호출 0회)을 Mock으로 단언 | ❌ | Feed 경로에 주입되는 외부 Client 빈이 아직 없다. 지금은 "호출할 대상이 코드에 없다"가 경계이고, FastAPI Client가 생기는 시점에 단언을 붙여야 한다 |
| N4~N7 쿼리 카운터 | ❌ | 일괄 조회로 구현했으나 카운터로 고정하지 않았다. 리팩터가 N+1을 다시 들여와도 테스트가 잡지 못한다 |
| `GET /feed/collections/{id}/shelf` | ❌ | 티켓 범위 밖(백엔드 합의) |
| 빈 후보 fallback(최신 발행만으로 응답) | ⚠️ | 후보 0건이면 빈 배열로 응답한다. 최신 채널이 이미 모든 발행분을 훑으므로 "후보 0건"은 곧 "보여줄 것이 없음"이고, 명세의 fallback이 추가로 건질 대상이 없다 |
