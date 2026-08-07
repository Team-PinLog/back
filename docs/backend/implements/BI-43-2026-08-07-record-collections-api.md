# BI-43. Record가 담긴 내 Collection 목록 조회 API

- **상태**: ✅ 완료
- **날짜**: 2026-08-07
- **관련**: S15P11A705-391, 명세 §5.10([Team-PinLog/docs#53](https://github.com/Team-PinLog/docs/pull/53) 선행 병합 필요),
  [BD-46](../decisions/BD-46-list-sort-default-asc-with-params.md)(정렬 방향·커서 부등호 짝),
  [BI-38](BI-38-2026-08-03-massive-scale-plan-observation.md)(이 실측이 미룬 `ix_colrec_collection` vs `uq_colrec_active` 판별을 여기서 채운다)

## 무엇을 만들었나

`GET /v1/records/{recordId}/collections` — Record 상세 화면에서 "이 기록이 담긴 내 책" 목록을
Collection 카드로 보여준다.

계약:

| 항목 | 내용 |
|---|---|
| 경로 | `GET /v1/records/{recordId}/collections?cursor=&size=20&sort=CREATED_AT_ASC` |
| 정렬 | `CREATED_AT_ASC`(기본) 또는 `CREATED_AT_DESC`. 동률은 `collectionId`로 끊는다 |
| 존재하지 않는 `recordId` | 404 |
| 타인의 `recordId` | 404 — 존재 여부를 구분하지 않는다(§5.2와 같은 규약) |
| 어느 Collection에도 없는 Record | `items: []`인 **200** — 404가 아니다 |
| `size` | 명세 공통 규칙대로 정규화(0·음수·상한 초과를 거절이 아니라 보정) |
| 잘못된 `sort` 값 | 400, `INVALID_INPUT` |
| 위조된 커서 | 400 |

산출물:

- `RecordCollectionController`(`domain/collection/controller`) — `GetMapping`, `CollectionSort` 파라미터.
- `RecordCollectionCardResponse`(`domain/collection/dto`) — `collectionId`·`title`·`recordCount`·
  `keywords`·`coverImageUrl`·`publishedAt`·`createdAt`.
- `CollectionRepository`에 4개 쿼리 메서드 추가 — 오름/내림차순 첫 페이지, 오름/내림차순 커서
  이후 페이지. 전부 `exists (select 1 from CollectionRecord cr where cr.collectionId = c.id and
  cr.recordId = :recordId)`로 연결을 확인하고, `@SQLRestriction`이 Collection·CollectionRecord
  양쪽에 `deleted_at IS NULL`을 자동으로 건다.
- `CollectionService.listByRecord` — 소유 확인(`requireOwnedActiveRecord`) 후 목록 조립.
- 테스트: `RecordCollectionApiTests`(계약 11개), `RecordCollectionQueryCountTests`(쿼리 수 고정 1개).

새 Flyway 마이그레이션·새 인덱스 없음 — 아래 "인덱스를 더하지 않은 근거" 참고.

## 경로는 Record 하위인데 코드는 collection 도메인인 이유

명세가 진입점을 `recordId`로 정했지만(사용자가 Record 상세에서 "이 기록이 담긴 책"을 본다),
반환 자원과 소유 판정 규칙은 전부 Collection이다 — 커서 정렬 기준(`created_at`), 소프트 삭제
판정, `PUBLIC` Keyword 집계, `recordCount`까지 모두 `CollectionRepository`·`CollectionService`가
이미 갖고 있는 것과 같은 규칙이다. `RecordController`에 얹으면 record 도메인이 Collection
서비스·리포지토리를 끌어와야 해서 의존 방향이 거꾸로 된다. 그래서 경로만 Record 하위에 두고
구현은 collection 도메인에 둔다(`RecordCollectionController` 클래스 주석에도 같은 근거를
남겼다).

소유 확인은 생략하지 않는다. `memberId` 필터만으로 끝내면 남의 `recordId`에도 빈 200이
돌아가 "담긴 Collection이 없다"와 "볼 수 없는 Record다"가 응답에서 구분되지 않는다. 그래서
`requireOwnedActiveRecord`가 먼저 `RecordRepository.findByIdInAndMemberId`로 소유·활성을
확인하고, 비어 있으면 `ResourceNotFoundException`(404)을 던진다.

## Keyword를 PUBLIC 범위로 모으는 판단

이 목록은 내 Collection만 보여주지만, 카드에 싣는 Keyword는 `ContextKeywordRepository
.findCollectionKeywordsPublic`(AI 도메인 소유, `ai.context_keyword` 조인)로 모은
**`PUBLIC` 범위 집계**다. 소유자 전용 필드가 아니다 — "내 책 목록"이지만 표지에 적히는
글자는 **남이 그 Collection을 볼 때 보는 것과 같아야 한다**(§5.10 명세: "남이 보는 표지와
같은 글자를 싣는다"). 소유자에게만 별도로 `BLOCKED`·`PRIVATE` Keyword까지 보여주면, 이
목록과 공개 상세(§7.3 `PublicCollectionDetailResponse`)가 같은 Collection에 대해 다른
Keyword 집합을 보여주는 불일치가 생긴다. AI 판정이 끝나지 않았을 때도 오류가 아니라 빈
배열이며, `keywordsIsAnEmptyArrayRatherThanNullWhenAiHasNotJudgedYet` 테스트가 그 경계를
검증한다.

집계는 페이지 전체의 `collectionId`를 모아 **한 번에** 조회한다(`toRecordCollectionCards`).
Collection마다 개별 조회하면 이 목록의 길이에 상한이 없다는 특성 때문에 컬렉션을 잘게 쓰는
사용자에게서만 드러나는 N+1이 된다 — `RecordCollectionQueryCountTests`가 항목 수가 2건에서
10건으로 늘어도 `ai.context_keyword` 조회가 정확히 1회로 유지됨을 측정으로 고정한다.

## 쿼리 수 고정 — 측정으로 검증

`RecordCollectionQueryCountTests.keywordLookupStaysOneQueryAsThePageGrows`가
`SqlQueryCounter`(실제 `Connection#prepareStatement` 호출을 센다)로 다음을 검증한다.

1. Collection 2건을 담은 Record로 조회 → `ai.context_keyword` 조회 1회를 기준선으로 잡는다.
2. Collection 8건을 담은 다른 Record로 조회 → `ai.context_keyword` 조회가 여전히 1회,
   `core.collection` 조회도 1회, `core.record` 조회(소유 확인)도 1회.

세 쿼리(Collection 페이지·소유 확인·Keyword 집계)가 항목 수 증가와 무관하게 그대로임을
"코드를 읽어 일괄 조회로 짰다"가 아니라 실측으로 못박는다. 결과: PASS.

```
./gradlew test --tests '*RecordCollectionQueryCountTests' --no-daemon
BUILD SUCCESSFUL in 36s
```

## 실행 계획 실측

### 측정 환경과 준비

로컬 dev 스택의 실제 컨테이너(`docker exec pinlog-postgres psql -U pinlog -d pinlog`)로 쟀다.
이 DB의 `core.collection`은 비어 있어 그대로 EXPLAIN을 뜨면 빈 테이블 순차 스캔만 나와
의미가 없으므로, 한 트랜잭션 안에서 합성 데이터를 넣고 측정 후 **`ROLLBACK`으로 되돌렸다**
(스크립트 전체가 `BEGIN`으로 시작해 `ROLLBACK`으로 끝나는 단일 psql 세션).

- member 1건, place 50건, record 50건(회원당 place별 1건), collection 300건.
- **첫 시도**: collection마다 collection_record 1건만 연결했더니 플래너가 `ix_colrec_collection`
  (`collection_id, created_at DESC`)으로 EXISTS를 풀었다 — 실제 운영에서는 한 Collection에
  Record가 보통 여러 개 담기므로, 이 결과가 대표성이 없다고 판단해 데이터를 다시 만들었다.
- **최종**: collection마다 collection_record **2건**을 연결해 다중 Record 현실을 재현했다.
  300건 중 70%는 대상 record를 담고(+ 다른 record 1건 동반), 나머지 30%는 대상 record 없이
  다른 record 2건만 담는다. `created_at`은 `now() + (g || ' seconds')`로 명시적으로 흩었다 —
  한 트랜잭션 안에서는 `now()`가 트랜잭션 시작 시각으로 고정돼, 그대로 두면 300건이 전부 같은
  시각이 되어 정렬 동률 상황만 보게 되기 때문이다.
- 로컬 dev DB의 `core.member`/`place`/`record` identity 시퀀스가 기존 시드 데이터(loadtest
  하네스)보다 뒤처져 있어(예: `member` 다음 identity 값이 1인데 id=1이 이미 존재), 그대로
  삽입하면 기존 행과 충돌한다. 시퀀스는 트랜잭션과 무관하게 영구적으로 바뀌므로 고치지 않고,
  어떤 테이블의 실제 max(id)와도 겹치지 않는 고정 오프셋(9억대)에 `OVERRIDING SYSTEM VALUE`로
  명시적 id를 지정해 넣었다. `ANALYZE core.collection; ANALYZE core.collection_record; ANALYZE
  core.record;`로 통계를 만든 뒤 측정했다 — 이걸 빼면 플래너가 빈 테이블 시절의 기본 추정치로
  계획을 고른다.
- 측정 뒤 `ROLLBACK`. `SELECT count(*) FROM core.collection` 등으로 실측 전후 행 수가 그대로임을
  확인했다(member 10,055 · place 50,000 · record 454,000 · collection_record 0, 전부 실측 전과
  동일).

### 오름차순 첫 페이지 (`LIMIT 21`, 명세 `size=20` 기준)

```
Limit  (cost=1.39..19.70 rows=21 width=576) (actual time=0.054..0.056 rows=21 loops=1)
  Buffers: shared hit=87
  ->  Incremental Sort  (cost=1.39..184.51 rows=210 width=576) (actual time=0.053..0.054 rows=21 loops=1)
        Sort Key: c.created_at, c.id
        Presorted Key: c.created_at
        Full-sort Groups: 1  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
        Buffers: shared hit=87
        ->  Nested Loop Semi Join  (cost=0.55..175.06 rows=210 width=576) (actual time=0.015..0.041 rows=22 loops=1)
              Buffers: shared hit=87
              ->  Index Scan Backward using ix_collection_member on collection c  (cost=0.27..36.46 rows=300 width=576) (actual time=0.006..0.010 rows=31 loops=1)
                    Index Cond: (member_id = '900000001'::bigint)
                    Filter: (deleted_at IS NULL)
                    Buffers: shared hit=3
              ->  Index Only Scan using uq_colrec_active on collection_record cr  (cost=0.28..0.45 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=31)
                    Index Cond: ((collection_id = c.id) AND (record_id = '900000201'::bigint))
                    Heap Fetches: 22
                    Buffers: shared hit=84
Planning:
  Buffers: shared hit=126
Planning Time: 0.355 ms
Execution Time: 0.071 ms
```

### 오름차순 커서 이후 페이지 (같은 세션, 20번째 항목의 `created_at`·`id`를 커서로 사용)

```
Limit  (cost=1.44..20.76 rows=21 width=576) (actual time=0.043..0.045 rows=21 loops=1)
  Buffers: shared hit=95
  ->  Incremental Sort  (cost=1.44..178.10 rows=192 width=576) (actual time=0.042..0.043 rows=21 loops=1)
        Sort Key: c.created_at, c.id
        Presorted Key: c.created_at
        Full-sort Groups: 1  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
        Buffers: shared hit=95
        ->  Nested Loop Semi Join  (cost=0.55..169.46 rows=192 width=576) (actual time=0.012..0.036 rows=22 loops=1)
              Buffers: shared hit=95
              ->  Index Scan Backward using ix_collection_member on collection c  (cost=0.27..38.71 rows=274 width=576) (actual time=0.006..0.014 rows=34 loops=1)
                    Index Cond: (member_id = '900000001'::bigint)
                    Filter: ((deleted_at IS NULL) AND ((created_at > '2026-08-07 07:45:32.772328+00'::timestamp with time zone) OR ((created_at = '2026-08-07 07:45:32.772328+00'::timestamp with time zone) AND (id > '900001026'::bigint))))
                    Rows Removed by Filter: 26
                    Buffers: shared hit=5
              ->  Index Only Scan using uq_colrec_active on collection_record cr  (cost=0.28..0.47 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=34)
                    Index Cond: ((collection_id = c.id) AND (record_id = '900000201'::bigint))
                    Heap Fetches: 22
                    Buffers: shared hit=90
Planning:
  Buffers: shared hit=16
Planning Time: 0.219 ms
Execution Time: 0.056 ms
```

### 읽는 법

- **`ix_collection_member`를 순서대로 탄다 — 별도 `Sort` 노드가 아니다.** `Index Scan Backward
  using ix_collection_member`가 `(member_id, created_at DESC)`을 뒤에서부터 읊어 `created_at
  ASC` 순서를 그대로 낸다. 다만 정확히는 "Sort 노드가 전혀 없다"가 아니라 **`Incremental
  Sort`가 있다** — `ORDER BY c.createdAt asc, c.id asc`가 두 컬럼인데 인덱스는 `created_at`까지만
  보장하므로, 동률(같은 `created_at`)이 있을 때 `id`로 마무리 정렬할 여지를 남긴다. 다만
  `Presorted Key: c.created_at`이 보여주듯 인덱스가 준 순서를 그대로 소비하는 경량 노드이고,
  이번 합성 데이터는 `created_at`이 전부 고유해 실제로 재정렬한 행은 없다(`Full-sort Groups:
  1`, `quicksort`, 메모리 27kB). 프로덕션에서도 `created_at`이 마이크로초 단위라 동률이 흔치
  않으므로 이 노드의 실비용은 낮다.
- **`exists`는 `uq_colrec_active`를 쓴다 — `ix_colrec_record`가 아니다.** 처음 collection당
  Record 1건으로 실측했을 때는 `ix_colrec_collection`(`collection_id, created_at DESC`)이
  나와서 브리프가 예상한 두 후보(`uq_colrec_active`/`ix_colrec_record`) 어느 쪽도 아니었다.
  collection당 Record 2건으로 다시 만들자 계획이 `uq_colrec_active`의 **Index Only Scan**으로
  바뀌었다 — `(collection_id, record_id) WHERE deleted_at IS NULL`이 `collection_id = c.id AND
  record_id = :recordId` 둘 다를 `Index Cond`로 흡수해서, `ix_colrec_collection`처럼 `Filter`로
  걸러낼 필요가 없다. `ix_colrec_record`(record_id만)는 이 조인 방향(collection이 바깥, record가
  안쪽에서 `collection_id = c.id`로 찾아가는 구조)에서는 애초에 후보가 아니다 — 그 인덱스는
  반대 방향("이 record가 담긴 collection\_id들")을 위한 것이다(주석에 "Record 삭제 시 역조회"로
  적혀 있다). `Heap Fetches: 22`가 0이 아닌 것은 방금 삽입한 합성 행이라 visibility map이 아직
  갱신되지 않아서다 — vacuum이 지난 뒤의 운영 데이터라면 더 줄어든다.
- **실제 읽은 행 수가 LIMIT 근처에서 멈춘다.** 대상 record가 300건 중 70%(210건)에 담겨
  있는데도, `Nested Loop Semi Join`이 21건을 채우기 위해 바깥쪽 `collection` 인덱스에서 실제로
  읽은 행은 31건(커서 없음)·34건(커서 이후, `Rows Removed by Filter: 26` 포함)뿐이다 — 300건
  전체나 210건 매치를 다 훑지 않는다. `Buffers: shared hit=87/95`도 디스크 읽기(`read`) 없이
  전부 캐시 적중이다.

### 계획이 뒤집혀도 테스트로는 안 잡힌다 — 이 기록이 기준선이다

위 계획이 나중에 (통계가 바뀌거나, 데이터 분포가 달라지거나, PostgreSQL 버전이 올라가서)
`ix_colrec_collection`이나 순차 스캔으로 바뀌어도, `RecordCollectionApiTests`·
`RecordCollectionQueryCountTests`는 **여전히 통과한다** — 둘 다 결과 집합과 쿼리 *개수*만
보고, 각 쿼리가 어떤 인덱스로 실행됐는지는 보지 않는다. 그래서 이 문서에 붙인 `EXPLAIN
(ANALYZE, BUFFERS)` 원문이 "언제부터 계획이 달라졌는가"를 되짚을 수 있는 유일한 기준선이다.
다음에 이 경로가 느려졌다는 신고가 오면, 여기 붙은 계획과 지금 계획을 나란히 놓고 어느 노드가
달라졌는지부터 본다.

## 인덱스를 더하지 않은 근거

세 후보 모두 이미 있는 인덱스로 충분했다.

1. **`c.memberId = :memberId` 필터** — `ix_collection_member (member_id, created_at DESC)`가
   그대로 감당한다. 이 인덱스는 §7.2(내 Collection 전체 목록)를 위해 이미 있었고, 정렬 기준이
   같은 `created_at`이라 재사용된다(그래서 정렬 기준을 "담은 시각"이 아니라 "Collection
   생성 시각"으로 잡은 명세 §5.10의 선택이 여기서 그대로 이득이 된다).
2. **`exists (... cr.collectionId = c.id ...)`** — `uq_colrec_active (collection_id, record_id)
   WHERE deleted_at IS NULL`이 위 실측대로 Index Only Scan으로 풀린다. 이 인덱스는 "Collection
   내 동일 Record 중복 금지"라는 무결성 제약으로 이미 있었던 것이라, 조회 성능이 무결성
   제약에 무임승차한 경우다.
3. **커서 이후 페이지의 부등호 조건** — 별도 인덱스가 없어도 같은 `ix_collection_member`
   스캔에 `Filter`로 얹힌다(실측의 `Rows Removed by Filter: 26`). Nested Loop의 안쪽 반복
   횟수(loops=31/34)가 300행 전체가 아니라 LIMIT에 근접한 수준이라, 이 Filter 비용이 무시할
   만하다.

새 인덱스를 추가하면 이 쿼리 하나의 계획에 영향을 주지 않으면서(위 세 인덱스가 이미
최적) `core.collection`·`core.collection_record`에 쓰기 비용(INSERT/UPDATE마다 인덱스 갱신)만
더할 뿐이다. 그래서 Flyway 마이그레이션을 추가하지 않았다.

## 남긴 것 / 다음 사람에게

- 이 실측은 collection당 Record 1~2건, 회원당 collection 300건 규모다. 회원당 collection이
  수만 건으로 늘어나거나 특정 Record가 담긴 비율이 지금과 크게 다르면(예: 1% 미만) 계획이
  달라질 수 있다 — [BI-38](BI-38-2026-08-03-massive-scale-plan-observation.md)이 그 갈림의
  선례다. 이 문서는 "지금 계획이 무엇인가"의 기준선이고, 규모가 실제로 그렇게 커지면 재측정이
  필요하다.
- `deleted_at IS NULL` 부분 인덱스(`ix_colrec_collection` vs `uq_colrec_active`) 판별은
  BI-38이 "소프트 삭제 행이 없는 벤치 볼륨이라 범위 밖"이라고 미뤄 둔 것이었다. 이번 실측은
  소프트 삭제 행 없는 상태에서 두 인덱스 중 `uq_colrec_active`가 선택됨을 확인했을 뿐, 삭제
  비율이 높아졌을 때의 선택은 여전히 미실측이다.
