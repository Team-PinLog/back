# BI-21. 삭제 경로의 AI 파생 데이터 무효화

- **상태**: ✅ 완료 (적용 지점 4 중 3 — 회원 탈퇴는 경로 미구현, 아래 "적용하지 못한 지점")
- **날짜**: 2026-07-29
- **관련**: S15P11A705-124, [back#61](https://github.com/Team-PinLog/back/issues/61),
  [BD-35](../decisions/BD-35-ai-derived-invalidation-inside-deletion-transaction.md),
  공용 계약 `Team-PinLog/docs` `static/06_데이터모델_및_무결성.md` §1.3·§6.4~6.9,
  `static/08_API_명세.md` "AI 파생 데이터"

정책의 정본은 `Team-PinLog/docs`의 `static/` 문서다. 이 문서는 **Spring에서 어떻게 구현했고
무엇을 검증했는가**만 다룬다.

## 산출

### `AiDerivedDataRepository` — 백엔드가 `ai`에 쓰는 전부

`domain/ai/repository/AiDerivedDataRepository`에 `NamedParameterJdbcTemplate` 기반 SQL 두 개를
담았다. JPA 엔티티로 매핑하지 않은 이유는 `FeedKeywordRepository`와 같다 — `ai` 스키마는 AI 파트
소유이고, 백엔드가 엔티티를 들면 소유하지 않은 스키마의 형상을 코드에 고정하게 된다.

```sql
UPDATE ai.context_ai_state
SET embedding_status = 'CANCELLED', keyword_status = 'CANCELLED', updated_at = now()
WHERE context_id IN (:contextIds);

UPDATE ai.context_embedding
SET is_deleted = true, updated_at = now()
WHERE context_id IN (:contextIds);
```

**클래스를 하나로 좁힌 것이 설계의 핵심이다.** 백엔드가 `ai`에 쓰는 경로가 이 파일 하나뿐이라,
쓰기 매트릭스(06 §1.3) 위반 여부를 리뷰에서 한 파일만 보고 판정할 수 있다. `embedding`·
`embedding_profile`·`PROCESSING`/`COMPLETED` 전이에 닿는 코드는 어디에도 없다.

지켜야 했던 세 가지:

- **`CANCELLED` 전이에 조건이 없다.** `WHERE ... AND status = 'PENDING'` 같은 가드를 걸지
  않는다. `ai.context_keyword`에 `is_deleted`가 없어 키워드 조회 제외를 `keyword_status`가
  단독으로 담당하므로, `COMPLETED`를 남기면 지운 Context의 Keyword가 계속 노출된다.
- **두 status를 모두 바꾼다.** `embedding_status`와 `keyword_status`는 독립 전이하는 별개 축이다.
- **영향 행 0이 오류가 아니다.** 임베딩·Keyword는 비동기 생성이라 커밋 직후에는 파생 데이터가
  아직 없다. `invalidate`는 반환값을 검사하지 않고, 빈 목록이면 쿼리 자체를 보내지 않는다
  (`IN ()`은 문법 오류다).

반대 방향의 안전장치는 이미 AI 워커 쪽에 있다. `ai_state_repo`의 `complete()`·`fail()`이
`WHERE {col} = 'PROCESSING'` 가드를, `try_start()`가 `status IN ('PENDING','PROCESSING')`을 걸어
`CANCELLED`로 전이한 뒤에는 워커가 덮지 못한다. **빠져 있던 것은 플래그를 켜는 쪽뿐이었다.**

### 적용 지점

| 계약 | 코드 | 호출 |
|---|---|---|
| 06 §6.5 Context 삭제 | `RecordDeletionService#deleteContext` | `context.softDelete()` 직후 |
| 06 §6.4 Context 수정의 구 Context | `RecordService#replaceContext` | `old.softDelete()` 직후 |
| 06 §6.6 Record 삭제(일반·강제) | `RecordDeletionService#cascadeDelete` | 활성 Context 전체를 한 번에 |
| 06 §6.9 회원 탈퇴 | — | 아래 참조 |

`cascadeDelete`는 `contextRepository.findByRecordId()` 결과를 지역 변수로 받아 소프트 삭제와
무효화가 **같은 목록**을 쓰게 했다. 두 번 조회하면 `@SQLRestriction`이 이미 지워진 행을 걸러
두 번째 목록이 비게 된다.

호출 위치는 전부 기존 `@Transactional` 메서드 안이다. 새 트랜잭션 경계도, 비동기도 없다
(BD-35). JPA의 소프트 삭제는 커밋 시점에 플러시되고 JDBC UPDATE는 즉시 나가지만, 서로 다른
테이블이라 순서가 결과를 바꾸지 않는다.

### 적용하지 못한 지점 — 회원 탈퇴(06 §6.9)

**백엔드에 회원 탈퇴 경로 자체가 없다.** 확인한 근거:

- 컨트롤러 전수 조사(`@DeleteMapping`·`@PostMapping`·`@RequestMapping`)에 `/v1/members` 계열
  매핑이 없다. `AuthTokenController`는 `refresh`·`logout`만 갖는다.
- `domain/member`에 서비스 클래스가 없다(`entity`·`repository`뿐).
- `src/main`의 `softDelete()` 호출 지점 전수 조사에서 Context를 지우는 경로는
  `RecordDeletionService`·`RecordService` 둘뿐이다.
- `SocialAccount` javadoc이 *"마스킹과 deleted_at을 한 UPDATE에 담는 도메인 메서드를 쓴다
  (탈퇴 티켓에서 추가)"* 라고 적어, 탈퇴가 별도 티켓임을 코드가 이미 명시한다.

탈퇴는 Refresh 전량 무효화·쿠키 만료·`provider_user_id` 마스킹·양방향 Follow 정리를 함께
요구하는 별개 유스케이스라, 이 티켓에서 만들면 계약 범위를 벗어난다. **탈퇴 티켓에서
`AiDerivedDataRepository.invalidate(contextIds)` 한 줄을 삭제 절차 끝에 붙이면 된다.**

## 검증

`AiDerivedDataInvalidationTests` (PostgreSQL Testcontainers, `pgvector/pgvector:0.8.5-pg16`).
AI 워커의 State·Embedding 생성이 아직 없으므로 파생 데이터를 직접 INSERT해 워커가 만들어 둔
상태를 재현한다. `VECTOR(1536)`이 NOT NULL이라 영벡터로 채운다(유사도를 보지 않는다).

| 테스트 | 고정하는 것 |
|---|---|
| `contextDeleteCancelsStateAndMarksEmbeddingDeleted` | 6.5 — 두 status `CANCELLED` + `is_deleted` |
| `contextReplaceInvalidatesOnlyTheOldContext` | 6.4 — 구 Context만. 같은 Record의 살아남은 Context는 그대로 |
| `recordDeleteInvalidatesEveryActiveContextOfThatRecord` | 6.6 일반 — 활성 Context 전체. 다른 Record는 무사 |
| `forceRecordDeleteInvalidatesEveryActiveContextOfThatRecord` | 6.6 강제 |
| `terminalStatusesAreOverwrittenByCancelled` | `COMPLETED`·`FAILED`도 덮는다(조건 없음) |
| `deletingAContextWithoutDerivedDataSucceeds` | Embedding 없음 / State·Embedding 둘 다 없음 → 204 |
| `forceDeletingARecordWithoutDerivedDataSucceeds` | 파생 데이터 없는 Record 통째 삭제 → 204 |
| `rejectedDeleteLeavesDerivedDataUntouched` | **409로 거절된 삭제는 파생 데이터도 건드리지 않는다** |

마지막 하나가 "무효화가 삭제 트랜잭션 **안에** 있다"를 고정한다. 밖으로 새어 나가면 거절된
요청이 검색에서만 Context를 지우는 상태가 만들어진다.

RED/GREEN 증거:

- RED — 세 호출부를 주석 처리하고 실행: `8 tests completed, 6 failed`. 실패하지 않은 둘은
  `forceDeletingARecordWithoutDerivedDataSucceeds`·`rejectedDeleteLeavesDerivedDataUntouched`로,
  **과잉 적용을 막는 음성 테스트**라 무효화가 없어도 통과하는 것이 정상이다.
- GREEN — 호출부 복원 후 `--tests "…domain.ai.*" --tests "…domain.record.*"`: 49 tests, 0 failed.
- Regression — `./gradlew clean check --no-daemon` 전량 통과.

## 남은 것

- **회원 탈퇴 적용**(06 §6.9) — 탈퇴 경로가 생기는 티켓에서.
- **`context_ai_state` 최초 생성**(`PENDING`) — 쓰기 매트릭스상 백엔드 몫이지만 Context 생성
  쪽이라 이 티켓 범위 밖이다. 그때까지는 삭제 시 State 행이 없어 UPDATE가 0행을 친다(정상).
- `ai` 스키마에 `context_id` 대량 UPDATE용 인덱스가 필요한지 — 둘 다 PK 조회라 현재는 불필요.
  탈퇴 배치가 붙을 때 재확인한다.
