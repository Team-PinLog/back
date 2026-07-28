# Feed 이벤트 수집

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Feed 노출·반응 이벤트를 저장하는 `core.feed_event` 테이블과 수집 경로를 정의합니다.

이벤트의 용도는 두 가지입니다.

- 노출 패널티 계산 (같은 Collection을 반복해서 상위에 올리지 않기)
- 추천 품질 관찰

MVP에서 이벤트를 학습 데이터로 사용하지 않습니다. 학습형 Ranking은 범위 밖입니다.

## 2. 테이블

`core` 스키마에 둡니다. Feed는 Spring 단독 기능이고 `ai` 스키마는 FastAPI가 소유하므로, 이벤트는 Core 소유입니다.

```text
core.feed_event

id             BIGINT      PK   -- GENERATED ALWAYS AS IDENTITY
member_id      BIGINT      NOT NULL   -- 이벤트를 발생시킨 User
collection_id  BIGINT      NOT NULL   -- 대상 Collection
place_id       BIGINT      NULL       -- CLICK/SAVE가 특정 Place를 향한 경우
event          VARCHAR(20) NOT NULL   -- IMPRESSION / CLICK / SAVE
request_id     UUID        NOT NULL   -- Feed Session 식별자
position       INT         NULL       -- 응답 목록에서의 0-based 순서
created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
```

### 2.1 컬럼 설명

| 컬럼 | 의미 |
|---|---|
| `member_id` | Feed를 요청한 본인. Collection 소유자가 아닙니다 |
| `collection_id` | Feed 추천 단위이므로 항상 채워집니다 |
| `place_id` | Collection 안의 특정 Place를 클릭·저장한 경우에만. IMPRESSION은 항상 `null` |
| `event` | 아래 3장 참조 |
| `request_id` | 같은 Feed 요청에서 나온 이벤트를 묶는 값. 위치 편향 관찰과 세션 단위 집계에 사용 |
| `position` | 상위 노출이 클릭률에 주는 영향을 분리해 보기 위한 값. IMPRESSION과 CLICK 모두 기록 |
| `created_at` | 시간 감쇠 집계 기준 |

### 2.2 제약과 인덱스

```sql
ALTER TABLE core.feed_event ADD CONSTRAINT ck_feed_event_type
    CHECK (event IN ('IMPRESSION', 'CLICK', 'SAVE'));

ALTER TABLE core.feed_event ADD CONSTRAINT ck_feed_event_position
    CHECK (position IS NULL OR position >= 0);

CREATE INDEX ix_feed_event_penalty
    ON core.feed_event (member_id, collection_id, created_at DESC);

CREATE INDEX ix_feed_event_request
    ON core.feed_event (request_id);

CREATE INDEX ix_feed_event_created
    ON core.feed_event (created_at);
```

- `ix_feed_event_penalty`가 핵심입니다. 노출 패널티는 매 Feed 요청마다 "이 User가 최근 이 Collection들을 몇 번 봤는가"를 조회하므로 이 인덱스에 전적으로 의존합니다.
- `ix_feed_event_created`는 보존 기간이 지난 행을 정리하는 배치용입니다.
- **FK를 걸지 않습니다.** 이벤트는 관측 로그이고, Collection이 삭제된 뒤에도 과거 이벤트가 남아 있어야 집계가 성립합니다. 무결성보다 쓰기 처리량과 삭제 자유도가 중요합니다.
- 소프트 삭제 컬럼(`deleted_at`)을 두지 않습니다. 사용자 소유 도메인 데이터가 아니라 append-only 로그입니다.
- 보존 기간은 유한해야 합니다. 무한 증식하면 패널티 쿼리가 느려집니다. 패널티 계산 윈도우(기본 7일)보다 넉넉한 기간만 남기고 `created_at` 기준으로 주기 삭제합니다.

## 3. 이벤트 종류

### 3.1 IMPRESSION — MVP의 의미

**IMPRESSION은 "서버가 Feed 응답으로 이 Collection을 전달했다"는 뜻입니다. 사용자 화면의 실제 viewport 노출이 아닙니다.**

이 구분이 중요합니다.

- 기록 주체는 **서버**입니다. Feed 응답을 만든 직후 Spring이 직접 기록합니다. 클라이언트가 보고하지 않습니다.
- 사용자가 스크롤하지 않아 실제로 보지 못한 항목도 IMPRESSION으로 기록됩니다.
- 따라서 `CLICK / IMPRESSION`을 실제 CTR로 해석하면 안 됩니다. 분모가 과다 계상되어 있습니다.
- MVP에서 이 부정확함을 감수하는 이유는, IMPRESSION의 용도가 CTR 측정이 아니라 **노출 패널티**이기 때문입니다. "이미 응답에 실어 보낸 것을 계속 상위에 올리지 않는다"는 목적에는 서버 기준 기록으로 충분합니다.
- 실제 viewport 노출을 측정하려면 클라이언트의 IntersectionObserver 보고와 체류 시간 기준이 필요합니다. MVP 범위 밖입니다.

기록 시점과 방식:

- Feed 응답을 반환하기 직전, 최종 선정된 항목 전체를 **한 번의 batch INSERT**로 기록합니다.
- 응답 트랜잭션을 막지 않도록 별도 스레드에서 수행합니다.
- 기록 실패는 로그만 남기고 응답은 정상 반환합니다. 이벤트 유실이 사용자 경험을 해치지 않습니다.
- `position`은 응답 목록의 0-based 인덱스입니다.
- 같은 `request_id`의 재요청(클라이언트 재시도)으로 중복 IMPRESSION이 생길 수 있습니다. 패널티 계산에서 `(request_id, collection_id)` 기준 distinct로 흡수하며 DB 유니크 제약으로 막지 않습니다.

### 3.2 CLICK

Feed 항목을 눌러 Collection 상세로 진입한 경우입니다. 클라이언트가 보고합니다.

- Collection 상세 진입 시 `collection_id`만, 상세 안에서 특정 Place를 눌렀으면 `place_id`도 함께 보냅니다.
- `position`은 클라이언트가 Feed 응답에서 받은 값을 그대로 돌려보냅니다.

### 3.3 SAVE

Feed에서 본 것을 자기 데이터로 가져간 경우입니다. 팔로우, 또는 해당 Place를 자기 Record로 추가한 행위를 의미합니다.

CLICK보다 강한 관심 신호이므로 향후 가중치를 분리할 수 있게 별도 이벤트로 둡니다. MVP의 패널티 계산에는 사용하지 않습니다.

## 4. 클라이언트 수집 엔드포인트

**IMPRESSION은 서버가 기록하지만 CLICK과 SAVE는 서버가 알 수 없습니다. 이를 수집할 엔드포인트가 반드시 있어야 합니다.**

```text
POST /api/core/v1/feed/events
```

요청:

```json
{
  "requestId": "b4f0...",
  "events": [
    { "event": "CLICK", "collectionId": 1024, "position": 3 },
    { "event": "SAVE",  "collectionId": 1024, "placeId": 88 }
  ]
}
```

응답: `204 No Content`

규칙:

- `member_id`는 요청 본문에서 받지 않습니다. 인증 컨텍스트에서 가져옵니다. 본문으로 받으면 타인 이벤트를 위조할 수 있습니다.
- `IMPRESSION`은 이 엔드포인트로 받지 않습니다. 서버가 기록하는 값이므로 클라이언트가 보내면 400으로 거부합니다.
- 배열로 받아 batch INSERT합니다. 이벤트마다 요청을 보내지 않습니다. 배열 크기 상한은 S15P11A705-117이 세운 규약을 따라 `global/common/InputLimits`에 이름 붙은 상수로 두고, 값은 `RECORD_IDS_MAX`·`CursorPage.MAX_SIZE`와 같은 **100**입니다 — 요청 배열마다 상한을 따로 정하면 "서버 방어 상한이 얼마인가"에 답이 여러 개가 됩니다.
- `requestId`가 실재하는 Feed Session인지 검증하지 않습니다. 관측 로그이므로 엄격한 검증보다 수집 성공률이 중요합니다. 다만 UUID 형식은 검증합니다.
- `collectionId`가 유효하지 않거나 삭제된 Collection이면 해당 이벤트만 조용히 버리고 나머지는 저장합니다. 부분 실패로 전체를 실패시키지 않습니다.
- 이 엔드포인트는 쓰기 전용입니다. 어떤 조회 결과도 반환하지 않습니다.

## 5. 패널티 집계 쿼리

노출 패널티 계산에 쓰는 형태입니다.

```sql
SELECT collection_id, COUNT(DISTINCT request_id) AS impressions
FROM core.feed_event
WHERE member_id = :memberId
  AND event = 'IMPRESSION'
  AND collection_id IN (:candidateIds)
  AND created_at > now() - INTERVAL '7 days'
GROUP BY collection_id;
```

- `COUNT(DISTINCT request_id)`를 쓰는 이유는 3.1의 재시도 중복을 흡수하기 위해서입니다.
- 후보 id 목록으로 범위를 좁혀 한 번만 조회합니다. Collection별 반복 조회를 하지 않습니다.
- 윈도우(7일)와 패널티 계수는 설정값입니다. [`feed-scoring.md`](feed-scoring.md)를 참조합니다.

## 6. 개인정보 관점

- `feed_event`는 "누가 무엇을 봤는가"의 기록이므로 개인 데이터입니다. 어떤 응답에도 노출하지 않습니다.
- Collection 소유자에게 "누가 내 Collection을 봤는지"를 제공하지 않습니다. 익명 서비스의 전제를 깨뜨립니다.
- 회원 탈퇴 시 해당 `member_id`의 이벤트는 개인정보 정책의 보존 기간에 따라 정리합니다. 탈퇴 즉시 Feed 계산에서는 사용되지 않습니다.
