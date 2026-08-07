# Feed 점수 계산

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

후보 채널, 점수 공식, 가중치, 다양성 조정, Cold Start를 정의합니다.

**이 문서의 모든 수치는 설정값이며 튜닝 대상입니다.** 초기값일 뿐 고정된 정책이 아닙니다. 코드에 상수로 박지 않고 `@ConfigurationProperties`로 외부화해 재배포 없이 조정할 수 있게 합니다.

전 과정이 Spring 메모리 내 산술입니다. FastAPI·Embedding·LLM을 호출하지 않고 벡터 유사도를 계산하지 않습니다.

## 2. 후보 생성

### 2.1 다중 채널

단일 채널로 후보를 만들면 필터 버블이나 최신순 나열 중 하나로 무너집니다. 성격이 다른 세 채널에서 뽑아 합집합을 만듭니다.

| 채널 | 목적 | 기본 배분 |
|---|---|---|
| 최신 발행 | 신규 Collection이 최소한 노출될 기회 | 100 |
| 팔로우 | 이미 관심을 표현한 Shelf | 80 |
| 탐색용 무작위 | 필터 버블 이탈 | 20 |

> **[BD-51 상충]** 백엔드는 탐색 탭(`GET /v1/feed/collections`)에서 팔로우 채널을 더 이상 후보에 합류시키지 않고, 최신·무작위 채널에서도 이미 팔로우한 회원의 Collection을 WHERE 절로 직접 제외합니다(제품 요구사항: 탐색은 신규 발견 목적이라 이미 관계를 맺은 회원의 책은 노출하지 않는다). 이 절이 말하는 "팔로우 = 80 배분·최우선 채널" 설계와 정면으로 상충합니다. 코드 쪽 결정과 감수한 것은 [BD-51](../../backend/decisions/BD-51-explore-excludes-followed-members.md)에 있습니다. 이 명세는 AI 파트 소유라 백엔드가 임의로 고치지 않았습니다 — 갱신 여부는 AI 파트가 판단합니다.

최신 발행 채널의 배분을 60에서 100으로 올린 것은 [P42](../proposals/P42-feed-mvp-without-place-metadata.md)에서 Place region 항을 제거한 데 따른 조치입니다. 제거 이전에는 region이 Keyword와 달리 AI 완료 여부와 무관하게 계산돼, AI가 미완료인 Collection도 점수 항 하나를 확보했습니다. 그 경로가 사라진 만큼 최신 채널을 늘려 후보 진입 기회를 넓혔습니다.

다만 이것은 **부분적인 보상입니다.** 채널 배분은 후보 풀 진입만 결정하고 점수에는 관여하지 않습니다. `keywordAffinity = 0`인 Collection은 후보에 들어온 뒤에도 `recency`(w=0.125) 하나로 경쟁하므로 점수 열세가 그대로 남습니다. 남는 한계는 P42의 "감수하는 것"에 적어둡니다.

각 채널 쿼리는 서로 독립이며 병렬 실행할 필요는 없습니다. 인덱스가 있으면 순차 실행으로 충분합니다.

### 2.2 채널별 쿼리 개요

```sql
-- 최신 발행
SELECT c.id FROM core.collection c
JOIN core.member m ON m.id = c.member_id AND m.deleted_at IS NULL
WHERE c.deleted_at IS NULL AND c.is_published = true
  AND c.member_id <> :me
ORDER BY c.published_at DESC
LIMIT :n;

-- 팔로우
SELECT c.id FROM core.follow f
JOIN core.collection c ON c.member_id = f.followee_member_id
                       AND c.deleted_at IS NULL AND c.is_published = true
JOIN core.member m ON m.id = c.member_id AND m.deleted_at IS NULL
WHERE f.follower_member_id = :me AND f.deleted_at IS NULL
ORDER BY c.published_at DESC
LIMIT :n;
```

무작위 채널은 최근 발행 구간에서 표본을 뽑되 `ORDER BY random()` 전체 스캔을 피하고 id 범위 기반 표본을 사용합니다.

`collection (is_published, published_at DESC) WHERE deleted_at IS NULL` 인덱스가 최신 발행 채널의 전제입니다.

### 2.3 합집합과 중복 제거

- 세 채널 결과를 합치고 `collection_id` 기준으로 중복을 제거합니다.
- 중복은 페널티가 아니라 **신호**입니다. 여러 채널에 걸린 Collection은 그만큼 관련성이 높다는 뜻이므로, 제거하되 어느 채널에서 왔는지는 보존합니다. 팔로우 채널 출처 여부가 점수 공식의 `followSignal`이 됩니다.
- 본인 소유 Collection은 모든 채널에서 제외합니다.
- 최종 후보 풀 크기는 **약 200**입니다. 합집합이 이보다 크면 채널 우선순위(팔로우 → 최신 → 무작위) 순으로 잘라냅니다.
- **현재 배분에서 이 잘라내기는 발동하지 않습니다.** 채널 배분의 합(100 + 80 + 20)이 `pool-size`와 같은 200이고 중복 제거는 개수를 줄이기만 하므로, 합집합은 항상 200 이하입니다. 배분 합을 `pool-size`보다 크게 올릴 때를 대비한 방어선으로 남겨두는 것이지 정상 경로에서 동작하는 규칙이 아닙니다.

```yaml
pinlog:
  feed:
    candidate:
      pool-size: 200
      recent-limit: 100
      follow-limit: 80
      random-limit: 20
```

`pool-size` 200은 상한이지 확보량이 아닙니다. 최종 20건에 대해 배분 합(200) 기준으로는 10배지만, 중복 제거 후 실제 후보 수는 그보다 적습니다. 팔로우가 0건인 사용자는 최신 100 + 무작위 20 = 120에서 시작해 중복 제거 후 더 줄어들어 **6배 남짓**이 됩니다. 이 정도면 다양성 조정과 재검증 탈락을 흡수합니다. 배분을 이보다 늘리면 특징 조회 비용이 선형으로 늘고, 줄이면 소유자 상한 적용 후 후보가 마릅니다.

## 3. 점수 공식

```text
score(c) =   w_follow   * followSignal(c)
           + w_keyword  * keywordAffinity(user, c)
           + w_recency  * recency(c)
           - impressionPenalty * min(impressions(user, c), cap)
```

각 항은 0~1로 정규화합니다. 그래야 가중치를 조정할 때 상대 비중이 직관과 일치합니다.

### 3.1 followSignal

```text
1.0  -- 팔로우 채널에서 나온 Collection
0.0  -- 그 외
```

이분값입니다. 팔로우 횟수나 기간으로 세분하지 않습니다.

> **[BD-51 상충]** 탐색 탭이 팔로우한 회원의 Collection을 후보 자체에서 제외하도록 바뀌어([BD-51](../../backend/decisions/BD-51-explore-excludes-followed-members.md)), 어떤 후보도 팔로우 채널에서 나올 수 없습니다. 그 결과 `followSignal`은 실질적으로 항상 0이고, `w_follow`(3.5)도 항상 곱해질 값이 없습니다. `w_follow`가 "가장 큰 가중치"라는 아래 3.5의 근거와 상충합니다.

### 3.2 keywordAffinity — weighted Jaccard

```text
keywordAffinity = Σ_k min(user[k], collection[k]) / Σ_k max(user[k], collection[k])
```

- `k`는 두 분포에 등장하는 모든 Keyword code의 합집합입니다.
- `user[k]`는 관심 Profile의 정규화 가중치, `collection[k]`는 Collection 특징의 정규화 가중치입니다. 없으면 0입니다.
- 분모가 0이면(양쪽 다 비어 있으면) 0으로 둡니다.
- 결과는 0~1입니다.

단순 교집합 개수 대신 weighted Jaccard를 쓰는 이유:

- 코사인 유사도는 Keyword 수가 많은 Collection에 유리하게 편향됩니다. 장소를 많이 담은 Collection이 무조건 상위에 오릅니다.
- 집합 Jaccard는 "조용한 곳을 아주 좋아함"과 "조용한 곳을 가끔 감"을 구분하지 못합니다. 가중치를 살려야 개인화가 됩니다.
- `min/max` 형태는 분포의 강도 차이를 반영하면서도 크기 편향을 억제합니다.
- 벡터 연산이 아니라 Map 순회 산술이므로 pgvector나 외부 호출이 필요 없습니다.

**입력 제약:**

- `user[k]`에는 본인의 `PUBLIC` + `PRIVATE_ONLY`가 들어갑니다.
- `collection[k]`에는 타인의 `PUBLIC`만 들어갑니다.
- `BLOCKED`는 양쪽 모두에서 제외합니다.
- 이 비대칭이 의도된 설계입니다. `PRIVATE_ONLY`가 collection 쪽에 들어가면 타인에게 감춰야 할 정보가 추천 결과를 통해 드러납니다.

### 3.3 recency

```text
recency = exp(-ageDays / halfLifeDays)
```

`published_at` 기준이며 기본 `halfLifeDays = 14`입니다. 선형 감쇠 대신 지수 감쇠를 쓰는 이유는 오래된 Collection이 완전히 0이 되어 영구히 배제되지 않게 하기 위해서입니다.

### 3.4 impressionPenalty

```text
penalty = impressionPenalty * min(impressions(user, c), cap)
```

- `impressions`는 최근 7일간 해당 User에게 그 Collection이 응답으로 나간 횟수(`COUNT(DISTINCT request_id)`)입니다.
- `cap`으로 상한을 두는 이유는, 상한이 없으면 한 번 상위에 올랐던 Collection이 노출 누적으로 영구히 하위에 고정되기 때문입니다. 회복 가능성을 남깁니다.
- IMPRESSION의 의미는 서버 응답 전달이며 실제 viewport 노출이 아닙니다. [`feed-event.md`](feed-event.md) 3.1을 참조합니다.

### 3.5 초기 가중치

```yaml
pinlog:
  feed:
    scoring:
      w-follow: 0.500
      w-keyword: 0.375
      w-recency: 0.125
      impression-penalty: 0.05
      impression-cap: 5
      impression-window: 7d
      recency-half-life-days: 14
```

**이 값들은 설정값이며 튜닝 대상입니다.** 초기값 선정 근거는 다음과 같으며, 고정된 정책이 아닙니다.

- `w_follow`가 가장 큰 이유는 팔로우가 사용자가 **명시적으로 표현한** 유일한 관심 신호이기 때문입니다. 추론값보다 명시값을 우선합니다.
- `w_keyword`가 두 번째인 이유는 AI 파생값이라 초기 데이터가 적을 때 신뢰도가 낮기 때문입니다. 데이터가 쌓이면 올릴 여지가 있습니다.
- `w_recency`가 가장 작은 이유는 이 값을 키우면 Feed가 사실상 최신순 목록이 되어 개인화가 무의미해지기 때문입니다.
- `impression_penalty = 0.05`, `cap = 5`이므로 최대 감점은 0.25입니다. 상위 항목을 뒤로 밀 정도는 되지만 완전히 배제하지는 않는 크기입니다.
- 기존 가중치에서 `w_geo_cat`만 제거한 뒤 나머지 세 항을 비례 정규화해 상대 순서를 보존합니다.
- 가중치 합이 1.0이 되도록 유지합니다. 합이 달라져도 상대 순서는 같지만, 점수 절대값을 로그로 비교할 때 해석이 어려워집니다.

튜닝은 이벤트 집계(3.4의 IMPRESSION과 CLICK/SAVE 분포)를 보고 수동으로 수행합니다. 학습형 Ranking이나 Multi-Armed Bandit을 도입하지 않습니다.

## 4. 다양성 조정

점수 상위 20개를 그대로 내보내면 같은 소유자의 Collection이 몰릴 수 있습니다.

| 규칙 | 기본값 |
|---|---|
| 한 응답 내 동일 소유자 Collection 최대 | 2 |
| 20개 중 탐색 슬롯 | **4** |

### 4.1 소유자 상한

점수 내림차순으로 순회하며 소유자별 카운트가 상한에 도달하면 건너뜁니다. 건너뛴 항목은 버리지 않고 뒤로 미룹니다. 후보가 마르면 상한을 무시하고 채웁니다. 빈 자리를 남기는 것보다 낫습니다.

### 4.2 탐색 슬롯

20개 중 **4개**는 점수 순위와 무관하게 탐색용 무작위 채널 후보에서 채웁니다. 탐색 비중은 **20%**입니다.

- 순수 exploit만 하면 Profile이 자기 강화되어 새로운 취향을 발견할 수 없습니다.
- 슬롯 수는 `page-size`에 비례합니다. `page-size`가 10에서 20으로 바뀔 때 슬롯을 2에서 4로 함께 올린 것은 탐색 비중 20%를 유지하기 위해서이며, 탐색의 강도를 바꾼 것이 아닙니다.
- 탐색 슬롯도 3장의 재검증과 소유자 상한을 동일하게 적용받습니다.
- 탐색 항목의 위치는 고정하지 않고 하위 절반에 분산 배치합니다. 상단을 무작위로 채우면 첫인상이 나빠집니다.
- 탐색 후보가 없으면 슬롯을 점수 상위로 채웁니다. 빈 자리를 남기지 않습니다.

```yaml
pinlog:
  feed:
    diversity:
      max-per-owner: 2
      exploration-slots: 4
      page-size: 20
```

역시 설정값입니다.

## 5. Cold Start

### 5.1 판정

```text
Profile.recordCount < coldStartThreshold  (기본 3)
   또는 Keyword 분포가 비어 있음
   또는 Profile 계산 실패
```

Record가 없는 신규 가입자뿐 아니라, Record는 있지만 AI Keyword가 아직 하나도 완료되지 않은 사용자도 여기에 해당합니다. AI가 비동기이므로 가입 직후 이 상태가 정상적으로 존재합니다.

### 5.2 처리

Cold Start에서는 가중치를 다르게 적용합니다.

| 항 | 일반 | Cold Start |
|---|---|---|
| `w_follow` | 0.500 | 0.500 |
| `w_keyword` | 0.375 | **0.000** |
| `w_recency` | 0.125 | **0.500** |

- `keywordAffinity`를 계산하지 않습니다. 입력이 없으므로 항상 0이고, 계산해봐야 모든 후보가 같은 점수를 받아 무의미합니다.
- `w_recency`를 올려 최신 발행 위주로 구성합니다.
- 팔로우가 있으면 그 신호를 그대로 씁니다. 신규 사용자도 온보딩에서 팔로우할 수 있습니다.
- Record가 0건이면 최신 발행 + 팔로우 + 무작위 조합이 됩니다.
- 탐색 슬롯은 Cold Start에서 오히려 늘립니다(기본 **6**, 20개 중 30%). 취향을 모르는 상태이므로 탐색 가치가 큽니다. 이 값도 `page-size`에 비례하며, 일반 20%보다 높게 잡은 비율 자체는 유지합니다.

Cold Start는 Profile이 채워지고 AI가 완료되면 자동으로 해소됩니다. 별도 전환 처리가 없습니다.

## 6. 계산 순서와 비용

```text
1. Profile 조회               Redis 1회 (miss 시 DB 집계)
2. 후보 생성                  DB 3회 (채널별)
3. Collection 특징 조회        Redis MGET 1회 + miss분 DB 1회
4. 노출 이벤트 집계            DB 1회
5. 점수 계산                  메모리
6. 다양성 조정                메모리
7. Core 재검증                DB 1회 (최종 20건 대상)
8. 상세 조립                  DB 1~2회
```

- 외부 호출은 0회입니다.
- DB 왕복은 후보 수에 비례하지 않습니다. 모든 조회가 id 목록 기반 일괄 조회입니다.
- 5~6은 후보 200건에 대한 Map 순회 산술이므로 밀리초 단위입니다.
