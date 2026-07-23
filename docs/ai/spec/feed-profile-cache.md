# Feed Redis Cache

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Feed가 사용하는 두 Cache의 구조, TTL, 무효화 정책, stale 방어를 정의합니다. Spring Data Redis를 사용합니다.

Cache는 Feed 요청 시 LLM·Embedding을 호출하지 않고도 특징 기반 점수를 계산할 수 있게 하는 장치입니다. Cache에 저장하는 것은 이미 DB에 있는 파생 데이터의 집계 결과뿐이며, Cache가 원본인 데이터는 없습니다.

## 2. 두 Cache

| Cache | Key | TTL | 내용 |
|---|---|---|---|
| 사용자 관심 Profile | `feed:profile:{memberId}` | **6시간** | 본인 Keyword 분포, region·category 분포, 팔로우 집합 |
| Collection 특징 | `feed:coll:{collectionId}` | **1시간** | `PUBLIC` Keyword 분포, region·category 분포, `record_count`, `published_at` |

TTL이 다른 이유:

- Profile은 사용자 본인의 축적된 기록에서 나옵니다. Record 하나가 추가돼도 분포가 크게 흔들리지 않으므로 길게 잡습니다. 계산 비용도 상대적으로 큽니다.
- Collection 특징은 타인에게 노출되는 값이고, 발행 직후 AI Keyword가 뒤늦게 채워질 수 있습니다. 짧게 잡아 AI 완료가 최대 1시간 안에 Feed에 반영되게 합니다.

두 TTL 모두 설정 외부화 대상입니다.

```yaml
pinlog:
  feed:
    cache:
      profile-ttl: 6h
      collection-ttl: 1h
      session-ttl: 10m
```

## 3. 값 구조

### 3.1 사용자 관심 Profile

```json
{
  "memberId": 42,
  "keywordWeights": { "QUIET": 0.31, "WITH_FRIENDS": 0.22, "WORK": 0.11 },
  "regionWeights":  { "서울 마포구": 0.4, "서울 성동구": 0.25 },
  "categoryWeights":{ "CAFE": 0.5, "RESTAURANT": 0.3 },
  "followeeIds": [7, 19, 105],
  "recordCount": 37,
  "builtAt": "2026-07-22T09:00:00Z"
}
```

- `keywordWeights`는 본인의 `PUBLIC` + `PRIVATE_ONLY` Keyword 빈도를 정규화한 값입니다. 합이 1입니다.
- `BLOCKED`는 집계 쿼리에서 제외합니다.
- `recordCount`는 Cold Start 판정에 사용합니다.
- `builtAt`은 진단용입니다. TTL과 별개로 언제 만들어졌는지 확인할 수 있어야 합니다.

`PRIVATE_ONLY`가 이 Cache에 들어간다는 점이 중요합니다. **이 값은 본인 Profile 계산에만 사용하며, 어떤 경로로도 응답이나 타인 Collection 특징으로 흘러가지 않습니다.** Profile은 점수 계산의 입력일 뿐 응답 DTO에 포함되지 않습니다.

### 3.2 Collection 특징

```json
{
  "collectionId": 1024,
  "ownerId": 7,
  "keywordWeights": { "QUIET": 0.5, "WITH_FRIENDS": 0.5 },
  "regions": ["서울 마포구"],
  "categories": ["CAFE"],
  "recordCount": 4,
  "publishedAt": "2026-07-20T11:30:00Z",
  "builtAt": "2026-07-22T09:00:00Z"
}
```

- `keywordWeights`에는 **`PUBLIC` Keyword만** 들어갑니다. `PRIVATE_ONLY`와 `BLOCKED`는 집계 쿼리의 WHERE 절에서 제외합니다.
- Keyword가 아직 없으면 빈 객체입니다. 이는 정상이며 오류가 아닙니다.
- `ownerId`는 다양성 조정(소유자 단위 상한)에 필요합니다. 응답에는 포함하지 않습니다.

## 4. 직렬화

- `RedisTemplate`에 `GenericJackson2JsonRedisSerializer`를 지정하고 key는 `StringRedisSerializer`를 사용합니다. JDK 직렬화를 쓰지 않습니다. 클래스 구조가 바뀌면 역직렬화가 깨지고, Redis에 무엇이 들었는지 육안으로 확인할 수 없습니다.
- 역직렬화 실패는 Cache miss로 처리합니다. 예외를 던지지 않습니다. 배포로 DTO 구조가 바뀌었을 때 Feed 전체가 죽는 것을 막습니다.
- Cache miss인 Collection id만 모아 **한 번의 DB 쿼리**로 채우고 `MSET` 계열로 일괄 저장합니다. id별 왕복을 하지 않습니다.

## 5. 무효화 — 이벤트 기반 무효화 없음

**Record 저장, Context 수정, Collection 발행, AI 완료 등 어떤 이벤트에서도 Cache를 능동적으로 무효화하지 않습니다. 만료는 오직 TTL로만 이루어집니다.**

이렇게 정한 이유:

- 무효화 대상 계산이 비쌉니다. 한 Context의 Keyword가 바뀌면 그 Record가 속한 모든 Collection의 특징 Cache와, 그 소유자를 팔로우하는 모든 User의 Profile Cache가 대상이 됩니다. 역방향 조회 비용이 Cache 이득을 상쇄합니다.
- 무효화 지점을 여러 서비스에 심으면 하나만 빠져도 stale이 남습니다. 그 stale은 TTL이 없으므로 영구적입니다. TTL만 두면 최악의 경우도 TTL 시간으로 상한이 잡힙니다.
- Feed는 정확성보다 신선도 상한이 중요한 기능입니다. 추천 순서가 1시간 늦게 반영되는 것은 결함이 아닙니다.
- AI가 비동기이므로 "AI 완료 시점"에 무효화하려 해도 Spring이 그 시점을 알 방법이 없습니다. FastAPI는 완료를 통보하지 않습니다.

예외는 두 가지뿐입니다.

- **회원 탈퇴**: 해당 User의 `feed:profile:{memberId}`를 즉시 DELETE합니다. 삭제 실패해도 정합성 문제는 없습니다(6장의 재검증이 막습니다).
- **Collection 삭제**: `feed:coll:{collectionId}`를 즉시 DELETE합니다. 같은 이유로 best-effort입니다.

두 경우 모두 Cache 삭제는 정합성 보장 수단이 아니라 불필요한 계산을 줄이는 최적화입니다.

## 6. Stale 방어

TTL만으로 만료하므로 Cache는 항상 최대 TTL만큼 오래된 값일 수 있습니다. 다음 규칙으로 방어합니다.

### 6.1 Cache에 담지 않는 것

다음은 **절대 Cache에 담지 않고 매 요청 DB에서 확인**합니다.

- `member.deleted_at`
- `collection.deleted_at`
- `collection.is_published`
- `record.deleted_at`, `collection_record.deleted_at`

삭제·비공개 상태가 stale하면 삭제된 데이터가 타인에게 노출됩니다. 이는 TTL로 감수할 수 있는 종류의 오차가 아닙니다.

Cache에 담는 것은 **순위에 영향을 주는 값**뿐이고, **노출 여부를 결정하는 값**은 담지 않습니다. 이 구분이 stale 방어의 핵심입니다.

### 6.2 최종 응답 전 Core 상태 재검증

**Feed 응답을 반환하기 직전, 최종 선정된 Collection에 대해 Core 상태를 다시 조회합니다.**

```sql
SELECT c.id
FROM core.collection c
JOIN core.member m ON m.id = c.member_id
WHERE c.id IN (:finalIds)
  AND c.deleted_at IS NULL
  AND c.is_published = true
  AND c.record_count > 0
  AND m.deleted_at IS NULL;
```

- 이 쿼리는 후보 전체(약 200)가 아니라 **최종 선정분(약 10)** 에만 수행합니다. 비용이 작습니다.
- 결과에 없는 id는 응답에서 제외합니다. 오류로 만들지 않습니다.
- 제외로 개수가 모자라면 점수 정렬의 다음 후보로 채우되, 채운 항목도 같은 재검증을 통과해야 합니다. 재검증 없이 채우면 방어선이 뚫립니다.
- Collection 상세 진입 시점에도 같은 검사를 다시 수행합니다. Feed 응답 이후 삭제될 수 있으며, 실패 시 403이 아니라 **404**를 반환합니다.

Feed·Library 조회는 `member`, `collection`, `collection_record`, `record`의 삭제 상태를 모두 확인해야 합니다. 한 단계만 빠져도 삭제된 데이터가 노출됩니다.

### 6.3 Redis 장애

Redis가 응답하지 않으면 Cache 없이 DB에서 직접 계산해 응답합니다.

- Redis 접근을 짧은 타임아웃으로 감싸고 예외를 Cache miss로 변환합니다.
- 연속 실패 시 일정 시간 Redis 접근을 건너뛰는 간단한 차단 로직을 둡니다. 매 요청마다 타임아웃을 기다리면 Feed 전체가 느려집니다.
- Redis 장애가 Feed 오류 응답이 되어서는 안 됩니다.

## 7. Feed Session Cache

Pagination을 위한 별도 Cache입니다.

| Key | TTL | 내용 |
|---|---|---|
| `feed:session:{requestId}` | 10분 | 점수 정렬이 끝난 Collection id 목록 |

- 첫 페이지 요청에서 후보 생성·점수 계산을 마친 뒤 결과 id 목록을 저장합니다.
- 이후 페이지는 이 목록에서 offset으로 잘라 쓰고, 잘라낸 항목에만 6.2의 재검증과 상세 조회를 수행합니다.
- 페이지마다 후보를 다시 생성하면 그 사이 점수가 바뀌어 중복·누락이 생깁니다.
- Session이 만료되었거나 없으면 새 Session을 만들어 첫 페이지부터 다시 시작합니다. 오류가 아닙니다.
- Session Cache에도 노출 여부 판정값은 담지 않습니다. id 목록뿐입니다.
