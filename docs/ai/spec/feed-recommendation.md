# Feed 추천 파이프라인

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Feed 추천의 전체 구조와 각 단계의 책임을 정의합니다. 점수 공식과 가중치는 [`feed-scoring.md`](feed-scoring.md), Cache는 [`feed-profile-cache.md`](feed-profile-cache.md), 이벤트 수집은 [`feed-event.md`](feed-event.md)에서 다룹니다.

Feed의 추천 단위는 **Collection**입니다. Record가 아닙니다.

## 2. 경계 — Spring 단독

Feed는 Spring Backend의 기능입니다. **Feed 요청 처리 중 다음을 호출하지 않습니다.**

- FastAPI의 어떤 API도 호출하지 않습니다.
- Embedding API를 호출하지 않습니다.
- LLM API를 호출하지 않습니다.
- 요청 시점에 벡터 유사도를 계산하지 않습니다.

Feed는 **이미 저장된 AI 파생 데이터와 Cache만** 사용합니다. MVP에서는
`ai.context_keyword`와 `ai.keyword_preset`을 읽기 조인하며 Place region·category는 사용하지 않습니다.

이 경계를 두는 이유:

- Feed는 사용자 요청 경로이며 응답 지연이 곧 체감 품질입니다. 외부 모델 호출은 지연과 비용이 예측 불가능합니다.
- AI 장애가 Feed를 중단시키지 않아야 합니다. AI는 기본 기능의 부가 계층입니다.
- Keyword가 아직 없는 Collection도 Feed에 노출될 수 있어야 합니다.

FastAPI는 Feed API를 제공하지 않고 Feed 점수를 계산하지 않습니다.

## 3. 파이프라인

```mermaid
flowchart LR
    A[Feed 요청] --> B[사용자 Profile 조회]
    B --> C[후보 생성]
    C --> D[Collection 특징 조회]
    D --> E[점수 계산]
    E --> F[다양성 조정]
    F --> G[Core 상태 재검증]
    G --> H[응답 조립]
    H --> I[IMPRESSION 기록]
```

### 3.1 사용자 Profile 조회

로그인 User의 관심 Profile을 Redis에서 조회합니다. 없으면 DB에서 계산해 채웁니다.

Profile 구성:

- 본인의 `PUBLIC` Keyword 분포
- 본인의 `PRIVATE_ONLY` Keyword 분포
- 팔로우 중인 `followee_member_id` 집합

`PRIVATE_ONLY`는 여기에만 쓰입니다. 타인 Collection의 특징 계산에는 절대 사용하지 않습니다. `BLOCKED`는 Profile 계산에서도 제외합니다.

### 3.2 후보 생성

여러 채널에서 Collection id를 모아 합집합을 만들고 중복을 제거합니다. 목표 후보 풀은 약 200건입니다.

- 최신 발행 Collection
- 팔로우한 Shelf의 Collection
- 탐색용 무작위 Collection

이 단계는 **id만** 다룹니다. 본문이나 특징을 함께 조회하지 않습니다. 채널별 쿼리와 배분은 [`feed-scoring.md`](feed-scoring.md)를 참조합니다.

최신 발행 채널의 배분을 60에서 100으로 올린 것은 P42에서 Place region 항이 빠지면서 AI 미완료 Collection이 점수를 얻던 경로가 사라진 데 대한 **부분적 보상**입니다. 후보 진입 기회만 넓힐 뿐 점수 열세는 해소하지 않습니다. 근거는 [`feed-scoring.md`](feed-scoring.md) 2.1에 있습니다.

본인 Collection과 이미 팔로우 관계가 아닌 탈퇴 User의 Collection은 후보에서 제외합니다.

> **[BD-51 상충]** 위 문장과 반대로, 실제로는 **이미 팔로우 관계인** 회원의 Collection이 탐색 탭 후보에서 제외됩니다. 팔로우 채널은 더 이상 후보에 합류하지 않고, 최신·무작위 채널도 WHERE 절에서 팔로우한 회원의 Collection을 직접 걸러냅니다. 근거와 감수한 것은 [BD-51](../../backend/decisions/BD-51-explore-excludes-followed-members.md)에 있습니다.

### 3.3 Collection 특징 조회

후보 id 목록으로 각 Collection의 특징을 **일괄** 조회합니다. Collection별 반복 조회를 하지 않습니다.

타인 Collection의 특징으로 사용할 수 있는 것은 다음뿐입니다.

- `PUBLIC` Keyword
- `record_count`
- `published_at`

`PRIVATE_ONLY`와 `BLOCKED`는 이 쿼리의 WHERE 절에서 제외합니다. 자바 코드에서 거르지 않습니다.

특징은 Redis에 캐시합니다. Cache miss인 id만 모아 한 번의 DB 쿼리로 채웁니다.

### 3.4 점수 계산

Profile과 Collection 특징을 조합해 점수를 계산합니다. 전부 메모리 내 산술이며 DB나 외부 호출이 없습니다.

### 3.5 다양성 조정

점수 순 상위만 뽑으면 같은 소유자·같은 지역이 몰립니다. 소유자 단위 상한과 탐색 슬롯을 적용해 조정합니다.

### 3.6 Core 상태 재검증

응답 직전에 최종 선정된 Collection에 대해서만 Core 상태를 다시 확인합니다. Cache가 stale일 수 있기 때문이며, 이 단계가 삭제된 데이터 노출을 막는 마지막 방어선입니다.

```text
member.deleted_at IS NULL
collection.deleted_at IS NULL
collection.is_published = true
collection.record_count > 0
```

탈락한 항목은 조용히 제외합니다. 부족분을 다음 후보로 채우려면 점수 정렬 결과에서 이어받되, 재검증을 다시 통과해야 합니다.

### 3.7 응답 조립

공개용 DTO만 사용합니다. Context 본문과 `member.id`를 포함하지 않습니다. Feed는 타인 데이터 접근 경로이므로 단일 공개 조회 서비스를 거칩니다.

Keyword가 없는 Collection은 빈 배열로 응답합니다. 오류가 아닙니다.

#### 3.7.1 표시 Keyword의 선정과 정렬

응답 `keywords`는 **최대 3개**이며 **빈도 내림차순**으로 정렬합니다. 상세와 기각한 대안은 [P46](../proposals/P46-feed-keyword-display-order.md)에 있습니다.

**출처가 서로 다르다는 것이 이 절에서 가장 중요합니다.**

| 무엇 | 값 | 출처 | 바뀌는 조건 |
|---|---|---|---|
| 개수 | 최대 3 | **프론트와 구두 합의 (2026-08-03)** | 화면 레이아웃이 바뀔 때 |
| 정렬 | 빈도 내림차순 | **프론트와 구두 합의 (2026-08-03)** | 화면 요구가 바뀔 때 |
| 동점 규칙 | 축 내 순위, 다음 `preset.id` | **back AI 파트의 판단** | 아래 근거가 무너질 때 |

앞의 둘은 **화면 요구에서 온 값이라 데이터 분포가 바뀌어도 재계산하지 않습니다.** 개수 3을 측정값으로 역산하지 마세요. 같은 날 4에서 3으로 한 번 바뀌었고, 그때 움직인 것도 화면 쪽이지 측정값이 아니었습니다. 뒤엣것은 우리 판단이므로 근거가 무너지면 바꿀 수 있습니다.

**정렬 키는 사전식으로 셋입니다.**

| 순위 | 키 | 방향 | 뜻 |
|---|---|---|---|
| 1 | 빈도 | 내림차순 | 그 Collection 안에서 해당 Keyword가 붙은 Context 수 |
| 2 | 축 내 순위 | 오름차순 | **같은 빈도·같은 `keyword_preset.category`** 안에서 `preset.id`로 매긴 1-based 순위 |
| 3 | `keyword_preset.id` | 오름차순 | UNIQUE 정수. 여기서 전순서가 완성됩니다 |

이 순서로 정렬한 뒤 앞에서 3개를 자릅니다. **선정 순서가 곧 표시 순서입니다.** 고른 뒤 다시 정렬하지 않습니다.

**축은 빈도를 뒤집지 않습니다.** 축 내 순위를 같은 빈도 안에서만 매기므로 서로 다른 빈도끼리는 비교에 끼어들 자리가 없습니다. 빈도 5·4·1이면 결과는 언제나 `[5, 4, 1]`입니다.

##### 왜 빈도가 1순위인가 — 합의를 바꾸지 않고 구체화합니다

프론트와 구두 합의된 것은 「빈도수 내림차순, 최대 3개」입니다. 빈도를 1순위에 두면 그 합의를 **바꾸는 것이 아니라 채우는 것**이 되어 재합의가 필요 없습니다. 빈도가 갈리는 Collection에서는 그 Keyword가 실제로 그 Collection을 대표한다는 뜻이므로 먼저 보여줄 값이 있습니다.

##### 왜 축이 2순위인가 — 빈도가 63%에서 아무것도 못 가릅니다

이쪽은 합의가 아니라 **실측에 근거한 우리 판단입니다.** 아래는 전부 **현 구현·상한 3 기준으로 다시 잰 값**입니다. 상한이나 정렬 순서가 바뀌면 이 표부터 다시 세워야 합니다.

```text
모든 Keyword의 빈도가 1인 Collection             10/16 (63%)   빈도 1순위가 하는 일이 없다
자를 필요가 있는 12건 중 3위·4위의 빈도가 같은 것   11/12 (92%)   잘리는 경계를 못 가른다
Keyword 4개 이상 → 자르는 일이 일어난다           12/16 (75%)   상한 4 시절의 38%에서 늘었다
```

Collection이 Record 1~5건 규모이고 Context 하나가 Keyword를 평균 2개 받아 같은 Keyword가 겹칠 일이 드뭅니다. **63%에서는 1순위가 아무 일도 하지 않고, 잘리는 경계도 92%가 동점입니다.** 동점 규칙을 `preset.id` 하나로 끝내면 그 자리가 사실상 무작위로 정해집니다.

빈도가 갈리는 Collection에서 축이 뒤로 밀리는 것은 잃는 것이 아닙니다. 빈도가 갈렸다는 것은 「우연히 쏠린 것」이 아니라 「실제로 그 축에 쏠린 Collection」이라는 뜻입니다.

- **`preset.id`로 푸는 것**은 표시값이 언제든 바뀌기 때문입니다. `display_name` 사전순으로 두면 라벨 한 글자를 고친 날 카드의 Keyword 구성 자체가 바뀝니다(`S15P11A705-252`가 같은 이유로 점수 계산의 키를 `code`로 못 박았습니다). `GROUP BY` 결과 순서는 DB가 보장하지 않으므로 후보가 아닙니다.
- **축을 그 앞에 두는 것**은 같은 자리를 어떻게 채울지의 문제입니다. 프리셋은 `COMPANION`(누구와)·`ACTIVITY`(무엇을)·`ATMOSPHERE`(어떤 분위기)·`SITUATION`(어떤 상황) 네 축이고, 동점 무리 안에서 한 바퀴 돌리면 카드가 한 문장으로 읽힙니다. `preset.id`만 쓰면 같은 축이 앞자리를 독점할 수 있습니다. `preset.id`만으로 동점을 푸는 경우와 비교하면 상위 3개의 축 커버리지가 **16건 중 5건에서 늘고 한 건도 줄지 않습니다**(다섯 건 모두 2축 → 3축).
- **엄격한 「축당 1개」는 쓰지 않습니다.** 3축 이상을 가진 Collection이 10/16이고 나머지 6건은 3축 미만입니다(1축 1건·2축 5건). 못 박으면 그 6건이 가진 것보다 적게 나갑니다. 라운드로빈이라 축이 하나뿐이어도 3칸이 찹니다.

##### 추천 점수와 가릅니다

추천 점수의 `keywordAffinity`는 보는 사람 기준(요청자 Profile과의 weighted Jaccard)이므로 표시에 쓰지 않습니다. 쓰면 같은 Collection이 보는 사람마다 다른 Keyword를 보여주고, `collection_id`로 잡힌 특징 Cache를 표시에 재사용할 수 없으며, **남의 카드에 내 Profile이 비칩니다.**

**개수는 설정값이 아니라 상수입니다.** 프론트가 이 수로 레이아웃을 확정하므로 배포마다 달라지면 계약이 아닙니다. [`feed-scoring.md`](feed-scoring.md)의 튜닝값과는 층이 다릅니다. 그쪽은 응답 계약에 드러나지 않는 내부 랭킹 거동입니다.

**경계 동작.**

| 상황 | 동작 |
|---|---|
| Keyword 0개 | `[]`. 오류가 아닙니다(공용 §16 시나리오 21) |
| Keyword 3개 미만 | 있는 만큼. 실측 16건 중 3건이 이 경로이며 정상 경로입니다 |
| 표시값을 못 찾은 `code`(폐기·비공개 전환) | 그 항목만 빠지고 **다음 후보가 그 자리를 채웁니다**. `code`로 대신 채우지 않습니다(08 §6.1) |
| 서로 다른 `code`의 표시값이 겹침 | 앞의 것만 남기고 **다음 후보가 그 자리를 채웁니다**. 칸을 비우지 않습니다 |

**정본.** 개수 상한과 "순서는 항상 같다"는 클라이언트가 보는 응답 계약이므로 공용 `static/08_API_명세.md` §10.1이 정본입니다(05 §14.6이 외부 API 정본을 08로 위임). 정렬 키와 동점 규칙의 근거는 이 문서와 P46이 담습니다.

> **[명세 갱신 필요]** 08 §10.1에 개수 상한 3과 순서 결정성이 아직 없습니다. `docs`는 별도 레포라 이 저장소의 변경으로 반영할 수 없습니다. 후속으로 남깁니다(P46 「명세 정본」).

### 3.8 IMPRESSION 기록

응답으로 내보낸 항목을 `core.feed_event`에 IMPRESSION으로 기록합니다. 이 기록이 다음 요청의 노출 패널티 근거가 됩니다.

## 4. API

```text
GET  /api/core/v1/feed/collections?cursor={opaqueCursor}&size=20
POST /api/core/v1/feed/events
```

- 한 페이지는 기본 20건입니다. `size`는 공통 커서 계약을 그대로 따릅니다. 기본값은 `CursorPage.DEFAULT_SIZE`(20), 서버 방어 상한은 `CursorPage.MAX_SIZE`(100)이며, 범위 밖 값은 `CursorPage.normalizeSize`가 보정합니다. Feed 전용 상한을 따로 두지 않습니다(S15P11A705-117이 세운 "서버 방어 상한의 답은 하나" 규약).
- `requestId`는 Feed Session 식별자이며 응답 본문과 CLICK·SAVE 이벤트 payload의 별도 필드입니다.
- `cursor`는 내부 구조를 노출하지 않는 opaque 문자열입니다. 서버는 cursor로 같은 Session의 다음 위치를 복원해 페이지 간 중복·누락을 방지합니다.
- 후보 풀 자체는 Session 단위로 Redis에 짧게 보관합니다. 매 페이지마다 후보를 다시 생성하면 정렬이 흔들립니다.
- Collection 상세는 Feed 전용 URL을 만들지 않고 공통 `GET /api/core/v1/collections/{collectionId}`를 재사용합니다.
- AI 처리가 끝나지 않은 Collection도 후보에 포함하며 응답에는 `keywords: []`를 넣습니다.
- 항목의 `keywords`는 **최대 3개**이며 **빈도 내림차순**입니다(프론트와 구두 합의, 2026-08-03). 순서는 같은 Collection에 대해 항상 같습니다. 동점 규칙까지 포함한 전체 규칙은 3.7.1에 있습니다.
- `POST /api/core/v1/feed/events`는 클라이언트가 CLICK·SAVE를 보고하는 엔드포인트입니다. 상세는 [`feed-event.md`](feed-event.md)를 참조합니다.

## 5. 실패 처리

| 상황 | 동작 |
|---|---|
| Redis 장애 | Cache 미사용으로 폴백해 DB에서 직접 계산. 응답은 정상 |
| Profile 계산 실패 | Cold Start 경로로 폴백 (인기·최신 위주) |
| 후보 0건 | 최신 발행 Collection만으로 응답. 빈 배열도 허용 |
| Keyword 없음 | 해당 항목의 keyword affinity를 0으로 두고 계속 진행 |
| IMPRESSION 기록 실패 | 로그만 남기고 응답은 정상 반환 |

Feed는 어떤 경우에도 AI나 Cache 때문에 500을 내지 않습니다.

## 6. 하지 않는 것

- 학습형 Ranking 모델을 두지 않습니다.
- Multi-Armed Bandit을 도입하지 않습니다.
- Collection Keyword를 물리 집계 테이블로 만들지 않습니다. 조인 집계와 Cache로 처리합니다.
- Feed 결과를 사용자별로 사전 생성(precompute)하지 않습니다. 요청 시점 계산입니다.
- 후보 생성에 벡터 검색을 쓰지 않습니다.
