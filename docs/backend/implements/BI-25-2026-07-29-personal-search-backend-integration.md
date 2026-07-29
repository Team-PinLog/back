# BI-25. 개인 자연어 검색 백엔드 연동

- **상태**: ✅ 완료
- **날짜**: 2026-07-29
- **관련**: S15P11A705-135, [back#61](https://github.com/Team-PinLog/back/issues/61),
  [BD-39](../decisions/BD-39-embedding-profile-in-application-config.md),
  [BD-36](../decisions/BD-36-pending-insert-in-transaction-process-call-after-commit.md)(back#82, 데이터 생성 선행),
  [BD-37](../decisions/BD-37-ai-derived-invalidation-inside-deletion-transaction.md)(back#80, 삭제분 제외 선행),
  공용 계약 `Team-PinLog/docs` `static/08_API_명세.md` §6.1,
  `static/05_AI_설계.md` §7.1·§9.1~§9.5,
  AI 파트 소유 명세 `docs/ai/spec/ai-integration.md` §2·§3·§6,
  `docs/ai/spec/ai-response-assembly.md` §4·§6

계약의 정본은 `Team-PinLog/docs`의 `static/` 문서다. 이 문서는 **Spring에서 어떻게 구현했고 무엇을
검증했는가**만 다룬다.

## 배경

시연 5단계 중 ② 자연어 검색이 비어 있었다. 계약(08 §6.1, 05 §9)도 FastAPI 구현
(`/internal/v1/search`)도 있는데 그것을 소비하는 백엔드 엔드포인트가 없었다. 선행 둘이 오늘 병합돼
검색 대상 데이터가 생기고(back#82) 삭제분이 빠지는(back#80) 경로가 갖춰졌다.

## 산출

### 흐름

```text
POST /v1/search/records
  → AiSearchClient          POST /internal/v1/search   (embeddingProfile 동봉)
  → SearchRecordRepository  Core 재검증 (소유권·삭제·활성 Context·Place)
  → ContextRepository       matchedContext 본문 조회 (memberId 조건 포함)
  → ContextKeywordRepository Record 단위 Keyword 집계
  → BoundsResponse.enclosing 재검증 통과분으로만 bounds 계산
```

### `AiSearchClient` — `process`와 실패 정책이 정반대다

`domain/ai/client`에 `AiProcessClient`와 나란히 두되, **모든 실패를 예외로 올린다.** 저쪽이 모든
실패를 삼키는 것은 fire-and-forget이고 `PENDING`이 남아 재스캔이 줍기 때문인데, 검색에는 그런
뒷수습이 없다. 삼키면 사용자에게 빈 결과가 보이고 그것은 "일치하는 기록이 없음"과 구분되지 않는다
(`ai` 레포 `docs/spec/model-profile.md` §3.1).

| 상황 | 응답 | `error.code` |
|---|---|---|
| 422 + 본문에 `serverProfile` | 503 | `SEARCH_PROFILE_MISMATCH` |
| 그 외 422 · 401 · 403 · 5xx · 연결 실패 · 타임아웃 | 503 | `SEARCH_UNAVAILABLE` |

**상태 코드만으로 Profile 불일치를 단정하지 않는다.** FastAPI는 요청 검증 실패(Pydantic)에도 422를
쓴다. 상태 코드로만 가르면 운영자가 있지도 않은 설정 불일치를 쫓게 되므로, 불일치 핸들러만 싣는
`serverProfile` 필드가 있는지로 판정한다. 이 분기는 테스트로 고정했다.

`code`를 둘로 가른 이유는 **사람이 해야 할 일이 정반대**여서다. 불일치는 배포 설정을 고쳐야 풀리고
재시도해도 그대로인 반면, 나머지는 대개 기다리면 낫는다. Profile 값 자체는 로그에만 남기고 응답에
싣지 않는다.

`RestClient` Bean은 `aiSearchRestClient`로 새로 만들었다. 타임아웃이 다르기 때문이며
(process 3s / search 5s), 타입이 같은 Bean이 둘이 되므로 **양쪽 주입부 모두** `@Qualifier`가 필요하다.
재시도는 넣지 않았다 — 사용자 요청 경로라 기다릴수록 손해고, 장애 시 재시도는 스레드 점유만 늘린다.

### `SearchRecordRepository` — Spring 최종 검증

FastAPI 응답을 그대로 내보내지 않는다(05 §9.5). `ai-response-assembly.md` §6.1의 SQL을 그대로 따랐고,
네 조건이 재검증 항목 하나씩에 대응한다.

```sql
WHERE r.id IN (:recordIds)
  AND r.member_id = :memberId              -- 소유권
  AND r.deleted_at IS NULL                 -- Record 삭제
  AND EXISTS (SELECT 1 FROM core.context ct
              WHERE ct.record_id = r.id AND ct.deleted_at IS NULL)   -- 활성 Context
-- JOIN core.place (INNER)                 -- Place 존재
```

재검증이 필요한 이유는 `ai.context_embedding.user_id`가 **비정규화 값**이라는 데 있다. 검색 범위
필터로는 충분해도 인가 근거로는 부족하고, 인가의 원본은 Core다. FastAPI는 애초에 인증을 판단하지
않는다.

**`matchedContext` 조회에도 같은 방어를 걸었다.** `ContextRepository.findByIdInAndMemberId`로
`memberId`를 조건에 두었고, 조립 단계에서 `context.recordId == match.recordId`까지 확인한다 —
Record는 내 것인데 Context id만 남의 것이 섞인 응답도 통과하지 못한다.

탈락은 **조용히 제외**한다(§6.3). 오류로 만들지 않고, 줄어든 개수를 다른 Record로 채우지도 않는다.
채우면 "유사도 상위 N개"라는 정렬 계약이 깨지고, 오류로 만들면 지워진 기록 하나 때문에 검색 전체가
실패한다.

### Record 단위 조립

유사도는 Context 단위로 계산되지만 응답은 Record 단위다(05 §9.4). FastAPI가 이미
`DISTINCT ON (record_id)`로 집계하지만 Spring도 **순서를 보존한 distinct**로 이중 보장한다.
먼저 온 것이 유사도가 높으므로 그것을 남기며, 그것이 곧 대표 `matchedContext`다.

`keywords`는 매칭 Context가 아니라 **Record의 활성 Context 전체 집계**다(08 §6.1). 매칭 Context만
보면 같은 Record의 다른 Context가 가진 Keyword가 사라진다. 가시성 필터는 SQL WHERE 절에 두고
(`visibility IN ('PUBLIC','PRIVATE_ONLY')`) `BLOCKED`는 화이트리스트 밖이라 어느 쿼리에도 등장하지
않는다 — 블랙리스트로 쓰면 나중에 추가되는 값이 그냥 통과한다(BD-13).

### `@Transactional`을 붙이지 않았다

`RecordSearchService.search`는 트랜잭션 밖에서 돈다. 붙이면 FastAPI 호출(읽기 타임아웃 5s) 내내 DB
커넥션이 잡히는데, `ai-integration.md` §4.1이 금지하는 형태가 정확히 그것이다. DB 조회 셋이 서로 다른
스냅샷을 봐도 무방하다 — 그 사이 무엇이 지워지든 결과는 "그 항목이 빠진다" 쪽으로만 움직이고,
검색은 애초에 움직이는 대상을 최선으로 재검증하는 일이라 한 스냅샷으로 묶는다고 더 정확해지지 않는다.

### 곁다리로 옮긴 것

- **`BoundsResponse`를 `domain/record/dto` → `global/response`.** 검색 응답이 지도(4.2)와 **같은
  규칙으로** 같은 형태를 내려주므로 두 도메인이 공유하게 됐고, 그러면 어느 한 도메인에 두지 않는다는
  패키지 규약을 따랐다. min/max 계산도 `enclosing` 정적 팩터리로 함께 올렸다 — 값만 공유하고 계산을
  도메인마다 다시 짜면 "결과 없음이 null인가 점 사각형인가" 같은 판단이 조용히 갈라진다.
- **`InputLimits.SEARCH_QUERY_MAX`(= 500).** 08 §1.9 상한 표에 검색 질의 항목이 **없다.** 명세가
  금지한 것이 아니라 아직 다루지 않은 자리이고, 상한이 없으면 임의 길이 문자열이 곧바로 외부 임베딩
  호출 비용이 된다(FastAPI 스키마도 `min_length`만 있고 상한이 없다). Context 본문과 같은 값으로
  두었다 — 질의도 그대로 임베딩 입력이라 이유가 같다. **명세에 반영되면 그 값이 정본이다.**

## 검증

`./gradlew clean check --no-daemon` — 통과.

`RecordSearchApiTests` 19개(PostgreSQL Testcontainers + JDK `HttpServer` FastAPI 대역).
`ConfigurationContractTests`에 BD-39를 파일 자체로 고정하는 단언 1개를 더했다.

**대역이 일부러 거짓말을 한다.** 남의 Context, Core에서 지운 Context, 지운 Record, 존재하지 않는 id를
결과로 돌려줄 수 있어야 "Spring이 FastAPI 응답을 믿지 않는다"를 증명할 수 있다. 진짜에 가까운 대역은
그 증명을 하지 못한다 — 진짜는 틀린 답을 주지 않기 때문이다.

| 항목 | 단언 |
|---|---|
| 계약 조립 | Record 단위 item · `matchedContext` · `keywords` · `bounds` · `similarity` |
| **Profile 불일치** | 422 → 200+빈 배열이 **아니라** 503 `SEARCH_PROFILE_MISMATCH` |
| 422 오분류 | 검증 오류 422는 `SEARCH_UNAVAILABLE`로 갈린다 |
| 호출 실패 | 5xx · 연결 끊김 → 503 `SEARCH_UNAVAILABLE` |
| **타인 소유** | 대역이 남의 Record·Context를 최상위로 돌려줘도 응답에 없다 |
| 삭제 | Core에서 지운 Context·Record가 결과에 없다 |
| 미상 id | 없는 Record id는 조용히 빠지고 `bounds`는 `null` |
| Record 단위 | 같은 Record의 Context 둘 → item 1개, 최고 유사도가 대표 |
| 순서 | FastAPI 유사도 내림차순이 재검증 뒤에도 유지된다 |
| Keyword | Record 전체 Context 집계 · `BLOCKED` 제외 |
| 설정 전달 | 설정에서 읽은 `embeddingProfile`·인증 memberId·시크릿 헤더가 실린다 |
| 요청 검증 | 빈 질의 400(호출 자체가 나가지 않음) · `size` 101 400 · 미인증 401 |

### 검증하지 않은 것

- **`ai.context_embedding.is_deleted = false` 필터.** 이것은 FastAPI의 SQL 필터(05 §9.3)이고 `ai`
  레포 테스트의 몫이다. 이 PR이 고정한 것은 **그 필터가 늦었을 때 Core 재검증이 막는다**는 쪽이다 —
  삭제와 검색 사이의 짧은 창을 대역으로 재현했다.
- **실제 FastAPI와의 통합.** 대역만 썼다. 두 서버를 붙인 스모크는 배포 게이트의 몫이다.

## 남긴 것

- **`ai` 레포 쪽 §7.1 대칭이 아직 없다.** 개정된 §7.1은 Profile 정본이 `ai` 레포
  `app/core/config.py`의 **기본값**이라 하고 근거로 `docs/proposals/P45-public-config-in-code.md`를
  드는데, 확인 시점(2026-07-29) `config.py`의 `embedding_profile`에는 기본값이 없고 P45 파일도 없다.
  계약이 먼저 가고 구현이 따라오지 않은 상태다. 이 티켓의 결정은 어느 쪽이든 성립한다(Spring은 자기
  설정값을 실어 보낼 뿐이다).
- **`docs/ai/spec/ai-integration.md` §2.1이 개정 전 §7.1을 그대로 담고 있다.** *"배포 환경의 단일
  설정에서 주입합니다"* — 오늘 뒤집힌 규칙이다. 같은 절의 `internal-token: ${PINLOG_AI_INTERNAL_TOKEN}`도
  구현(`pinlog.ai.internal-secret`)과 다르다. 그 문서는 AI 파트 소유 구역이라 고치지 않고
  CLAUDE.md 9번에 따라 해당 지점에 표시만 남겼다.
- **검색 질의 길이 상한이 08 §1.9에 없다.** 위 "곁다리" 참조. 백엔드 방어로 500을 두었고 명세 반영을
  기다린다.
- **`FeedKeywordRepository`와 `ContextKeywordRepository`가 둘 다 `ai.context_keyword`를 읽는다.**
  전자는 Collection·Profile 가중치 집계, 후자는 Record 단위 응답 조립이라 쿼리 목적이 다르지만,
  `ai` 접근이 두 패키지로 갈려 있는 것은 사실이다. 합칠지는 세 번째 소비자가 생길 때 판단한다.
