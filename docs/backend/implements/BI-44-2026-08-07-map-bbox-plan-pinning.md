# BI-44. 지도 bbox 쿼리를 custom plan에 묶는다

- **상태**: ✅ 완료
- **날짜**: 2026-08-07
- **관련**: Jira S15P11A705-404, 브랜치 `perf/S15P11A705-404-map-bbox-plan`,
  [BI-42](BI-42-2026-08-07-map-keyword-chips.md)(bbox SQL을 두 갈래로 나눈 근거),
  [BI-37](BI-37-2026-08-02-load-profiles-report.md)(부하 프로파일),
  [BI-38](BI-38-2026-08-03-massive-scale-plan-observation.md)(대량 볼륨 계획 관측)

## 무엇을 고쳤나

지도 bbox 조회 두 개가 **실사용에서만** 느린 실행 계획을 타고 있었다.

- `GET /v1/records/map` (bbox 있는 경로) — `RecordRepository.findMarkersWithinBounds`
- `GET /v1/records/map/keywords` (bbox 있는 경로) — `ContextKeywordRepository.findTopKeywordsInBounds`

두 서비스 메서드에서 bbox가 넷 다 주어진 경우에만 `SET LOCAL plan_cache_mode = force_custom_plan`을
걸어 계획 캐시를 끈다. 새 컴포넌트 `QueryPlanPin` 하나가 그 문장을 쥐고 있고, 호출은 두
자리(`RecordService.map`·`RecordService.mapKeywords`)뿐이다.

## 왜 리터럴 EXPLAIN이 이걸 놓쳤나

**이 리포트의 핵심이다.** pgjdbc는 같은 문장을 5회 실행한 뒤 서버 프리페어로 전환하고, 그 시점에
Postgres가 generic plan을 고를 수 있다. generic plan은 파라미터 값을 모르므로 bbox가 실제로 얼마나
걸러내는지 읽지 못한다.

리터럴을 넣어 `EXPLAIN`을 뜨면 **항상 custom plan**이라, 이 전환은 검증에서 절대 드러나지 않는다.
BI-42가 남긴 성능 근거도 리터럴 측정이었다.

드러내려면 `PREPARE` 후 **6회 이상** 실행한 계획을 봐야 한다. record 1,012만 볼륨(대량 벤치 시드)에서
실측한 결과다.

| 쿼리 | 1~5회차 (custom plan) | 6·7회차 (generic plan) | 배율 |
|---|---|---|---|
| 마커 bbox | 2.76ms | 45.4 / 61.6ms | **16~22배** |
| 키워드 bbox | 5.45ms | 32.0 / 34.1ms | **6배** |

generic plan은 **조인 순서를 뒤집는다.** 회원의 record(697건)에서 출발해 place를 PK로 찌르는 대신,
`ix_place_lat_lng`로 bbox 안 place 24,503건을 훑고 건마다 record를 찔러 대부분 0행을 얻는다.

```
->  Index Scan using ix_place_lat_lng on place p (actual time=0.030..12.976 rows=24503 loops=1)
      ->  Index Scan using uq_record_active on record r (actual time=0.001..0.001 rows=0 loops=24503)
```

지도 화면은 항상 bbox를 보내고 운영 커넥션은 오래 살아 있으므로, 이것은 드문 경우가 아니라
**사실상 상시 경로**다.

### SQL을 두 갈래로 나눈 것으로는 막지 못한다

BI-42는 `(:swLat IS NULL OR ...)`로 두 경로를 합치지 않은 이유를 "합치면 플래너가 bbox 선택도를
못 읽어 실행 계획이 나빠진다"로 적었다. 그 판단 자체는 맞다 — 나누면 플래너가 **bbox 조건의 존재**를
안다. 그러나 **값**은 여전히 파라미터이고, generic plan은 그 값을 못 읽는다. 나누는 것으로 얻는 것과
계획 캐시가 가져가는 것이 다른 층위다.

## 어떻게 고칠지 — 후보 넷을 재봤다

| 후보 | generic plan 구간 실행 시간 | 판정 |
|---|---|---|
| **`plan_cache_mode = force_custom_plan`** | **4.63ms** (키워드) / 2.76ms (마커) | ✅ 채택. 계획이 회원 인덱스에서 출발하는 상태로 복원된다 |
| bbox 없는 `MATERIALIZED` CTE로 회원 record 먼저 고정 | 18.94ms | ⚠️ 절반만 회복. 플래너가 bbox 안 place 24,503건을 해시로 쌓는다 |
| bbox 포함 `MATERIALIZED` CTE | 33.17ms | ❌ 효과 없음. CTE 안에서 같은 순서로 뒤집힌다 |
| 데이터소스 전역(`prepareThreshold=0` 등) | — | 보류. 다른 쿼리의 계획 재사용 이득까지 버린다 |

**CTE로 감싸는 방식이 듣지 않는다는 것이 직관과 반대여서 적어 둔다.** "회원 record를 먼저 고정하면
플래너가 그 순서를 지킬 것"이라는 기대가 실측에서 빗나갔다. 다음에 같은 증상을 만나면 CTE부터
시도하지 말 것.

전역 설정을 보류한 이유는 범위 판단이 아직 없기 때문이다. 어디까지 걸어야 하는지는
**S15P11A705-405의 전수 감사**가 정한다. 그때까지는 실측으로 확인된 두 자리에만 건다.

## bbox 없는 경로에는 걸지 않았다

`TOP_KEYWORDS_FOR_OWNER_SQL`(bbox 생략 시 전체 집계)에는 그 SQL 주석이 밝힌 자기 취약점이 따로 있다
— 회원당 Context가 6,000 부근에서 `ai.context_ai_state` Seq Scan으로 뒤집힌다. 여기서 함께 걸면
감사 결과를 미리 단정하는 것이 되므로 남겨 두었고, 테스트가 그 경계를 고정한다.

## 실사용 실측 (수정 전/후)

대량 볼륨(record 1,012만)에서 같은 엔드포인트를 순차로 20회 호출했다. pgjdbc가 5회 후 갈아타므로
뒷부분이 실사용 상태다. 10~20회차 중앙값이다.

| 경로 | 수정 전 | 수정 후 |
|---|---|---|
| 마커 bbox (HTTP) | 67.5ms | **38.8ms** |
| 키워드 bbox (HTTP) | 72.1ms | **25.6ms** |

수정 전에는 계단이 뚜렷했다 — 마커는 9회차부터 27~45ms에서 65~87ms로, 키워드는 10회차부터
22~37ms에서 60~104ms로 뛰었다. 수정 후에는 그 계단이 사라진다.

**HTTP 수치가 DB 배율(16~22배)만큼 좋아지지 않는 것은 정상이다.** 마커 응답은 418건을 조립하고 최근
컬렉션 id를 붙이고 Java `Collator`로 정렬하는데, 그 시간은 계획과 무관하다. DB 밖 비용이 남는 만큼
개선 폭이 줄어든다.

측정 환경에 자원 한도를 얹지 않았다(`compose.bench.yaml` 미적용). 1,012만 행이 전부 페이지 캐시에
들어간 상태이므로 **디스크 I/O가 빠진 하한값**이며, 절대 수치를 운영 예측에 쓰면 안 된다. 배율과
계획 모양이 이 측정의 산출물이다.

## 테스트가 보장하는 것과 못 보는 것

`MapBboxPlanCacheTests`(5개)가 덮는 것:

1. bbox 경로가 도는 트랜잭션의 `plan_cache_mode`가 실제로 `force_custom_plan`이다 (마커·키워드 각각)
2. bbox 없는 경로는 `auto`로 남는다 — 위 두 단정의 판별력을 만드는 음성 대조
3. 고정한 상태에서 프리페어 기준(7회)을 넘겨도 `pg_prepared_statements.generic_plans` 증분이 0이다
4. 같은 자리에서 `force_generic_plan`으로 뒤집으면 그 계수가 오른다 — 계수가 관측 도구로 쓸 만하다는 확인

수정을 되돌리면 이 중 3개가 깨지는 것을 확인했다(계수 증분 2).

**계획 모양은 단정하지 않는다.** 조인 순서 뒤집힘은 bbox 안 place 수가 회원 record 수보다 훨씬 많아야
일어나고, Testcontainers DB는 그 조건을 만들 수 없다. 계획을 단정하면 고치기 전에도 통과해 판별력이
없고, 억지로 데이터를 맞추면 `FeedChannelPlanTests`가 겪은 플레이크(행 수·통계 갱신 타이밍에 판정이
붙었다 떨어졌다 하는 것)를 되풀이한다. 그래서 계획 모양과 실행 시간은 위 실측으로 갈음하고 이 문서에
남긴다.

**계수를 절대값이 아니라 증분으로 본다.** 프리페어된 문장은 트랜잭션이 끝나도 풀 커넥션에 남고 계수도
함께 남는다. 절대값으로 단정했더니 같은 커넥션을 먼저 쓴 테스트가 올려 둔 값까지 세어 실행 순서에 따라
판정이 갈렸다(실측으로 3이 새어 들어왔다).

## 남은 것

- **전수 감사**(S15P11A705-405) — 같은 실패가 범위·부등호·`LIKE`·`IN`을 쓰는 다른 쿼리에도 있는지.
  피드·검색은 AI 파트 소유라 그 티켓이 전달 문구까지만 만든다.
- **bbox 없는 키워드 집계**의 계획 취약점 — 위 절 참조. 감사가 판단한다.
- **자원 한도를 얹은 재측정** — 이 측정의 한계를 해소한다.
- `SET LOCAL`은 트랜잭션 밖에서 경고만 남기고 아무 일도 하지 않는다. 호출부의
  `@Transactional(readOnly = true)`가 사라지면 보호가 조용히 없어지는데, 위 테스트 1이 그 상태를 잡는다.
