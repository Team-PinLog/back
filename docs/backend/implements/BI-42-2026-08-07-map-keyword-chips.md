# BI-42. 지도 bbox 안 상위 키워드 조회 API를 추가한다

- **상태**: ✅ 완료
- **날짜**: 2026-08-07
- **관련**: Jira 작업(작업 내용·완료 조건·성능 근거 요약이 본문에 있다),
  브랜치 `feat/{jira-key}-map-keyword-chips`, [BD-13](../decisions/BD-13-public-boundary-query-dto-split.md)

## 무엇을 만들었나

지도 화면 검색창 밑에 띄울 키워드 칩을 위해 `GET /v1/records/map/keywords`를 더했다. bbox 안에 있는
내 Record를 기준으로, 거기 붙은 AI 키워드를 Record 단위 `COUNT(DISTINCT record_id)`로 세어 상위 5건을
돌려준다.

```json
{ "items": [{ "keywordId": 12, "displayName": "카페", "recordCount": 12 }] }
```

`keywordId`를 반드시 싣는다. 칩을 눌러 지도 필터로 넘길 때(Jira 작업) 쓸 값이라, 표시 이름으로
넘기면 동명 프리셋과 표기 변경에 깨진다.

bbox 파라미터(`swLat`·`swLng`·`neLat`·`neLng`)는 `GET /v1/records/map`과 같은 규칙을 쓴다 — 넷 다
주거나 전부 생략해야 하고, 일부만 주면 400이다. 이 검증을 `RecordService.requireWholeBbox`로 뽑아
둘이 공유한다.

## `ContextKeywordRepository`에 넣은 이유

`ai` 스키마를 읽는 코드는 이 저장소 한 곳으로 유지한다. 가시성 화이트리스트
(`visibility IN ('PUBLIC', 'PRIVATE_ONLY')`, `is_active = true`, `keyword_status = 'COMPLETED'`)가
두 군데로 갈라지면 나중에 추가되는 visibility 값이 한쪽에서만 새어 나갈 수 있고, 그 누락은 곧 개인정보
노출이다(BD-13). `BLOCKED`는 화이트리스트 밖이라 자동으로 제외된다 — 블랙리스트로 짰다면 나중에
추가되는 값이 그냥 통과했을 것이다.

## `core.record` 조인은 bbox 때문이지 삭제 필터 때문이 아니다

Record를 지우면 `RecordDeletionService`가 그 Record의 Context를 전부 소프트 삭제하므로,
`ct.deleted_at IS NULL`이 삭제된 Record를 걸러내는 일은 이미 하고 있다. `core.record` 조인이 있는
이유는 오직 `p.lat`·`p.lng` BETWEEN으로 bbox를 걸기 위해서다.

이 조인을 삭제 목적으로 착각해 bbox가 없는 전체 집계 경로에도 넣으면 플래너가 `record` 테이블 전체를
Seq Scan 한다 — 3,000 Context 회원 기준 73ms(조인 있음) 대 17ms(조인 없음)로 갈렸다. bbox 있는
경로와 없는 경로가 SQL을 공유하지 않는 이유다.

## bbox 한정이 계획 역전을 없앤다

`ai.context_ai_state` 접근이 인덱스 경로를 타는지 전체 Seq Scan을 타는지는 플래너의 비용 추정
경계에 달려 있는데, 이 경계는 통계가 조금만 달라도 넘어간다. 같은 Context 6,000건을 가진 회원 둘이
다른 계획을 받아 16ms와 49ms로 3배 차이가 났다 — `FeedChannelPlanTests` 플레이크와 같은 종류의
계획 역전이다.

bbox로 먼저 Record를 좁히면(`ix_place_lat_lng`) 플래너가 전체 스캔을 고려할 이유 자체가 없어진다.
Context 40,000건인 회원도 인덱스 경로를 유지하며 29.5ms에 끝난다(전체 집계였다면 94ms·Seq Scan).
bbox 기준 집계는 화면 정합성(칩 숫자와 핀 수가 일치해야 한다)뿐 아니라 계획 안정성 때문에도 옳은
선택이다.

## 캐시와 집계 테이블을 두지 않은 이유

성능은 이미 충분하다(위 수치). 두지 않은 진짜 이유는 정합성이다.

- `ai.context_keyword`에 INSERT하는 주체는 FastAPI다. 백엔드가 `ai` 스키마에 쓰는 것은 삭제 경로의
  `context_ai_state → CANCELLED`와 `context_embedding.is_deleted`뿐이라, 증분 집계를 걸 지점이
  우리에게 없다. 만들면 감소만 반영되고 증가는 못 세는 반쪽 집계가 된다.
- `keyword_preset.visibility`가 `BLOCKED`로 바뀌거나 `is_active`가 꺼지면, 온더플라이 조회는 다음
  요청부터 바로 빠지지만 집계 테이블은 재계산 전까지 계속 노출한다. BD-13이 경계한 실패 모드가
  테이블 형태로 재현되는 것이다.

**다시 검토할 조건**: 회원당 Context가 3,000을 넘기 시작하고 p95가 실측으로 흔들릴 때. 그때도 먼저
쓸 수단은 집계 테이블이 아니라 Redis TTL 캐시다 — TTL이 가시성 스냅샷 문제를 자동으로 걷어내고
쓰기 훅이 필요 없다.

## 남은 것

- 지도 필터(`GET /v1/records/map`에 `keywordId` 추가) — Jira 작업
- 공개 API 명세 반영 — [docs#52](https://github.com/Team-PinLog/docs/pull/52)로 올라감(머지 대기).
  `08_API_명세.md` 2.2·4.2·4.3에 지도 키워드 칩 조회 API와 마커 키워드 필터가 반영돼 있다.

## 검증

`./gradlew clean check --no-daemon` 통과. `RecordMapKeywordsApiTests` 20건(실행 기준, 파라미터
테스트 포함) — bbox 안/밖, 가시성 화이트리스트(`BLOCKED`·비활성·`PENDING`/`PROCESSING`/`FAILED`/
`CANCELLED` 제외, `PRIVATE_ONLY` 포함), Record 단위 중복 제거, 동점 `keywordId` 정렬, 상위 5건이
가장 큰 값이라는 계약, `core.record`·`core.context` 각각의 소프트 삭제 제외, bbox 있는/없는 경로
양쪽의 소유권 격리, 빈 결과 계약을 고정한다.
