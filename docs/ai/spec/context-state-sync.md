# Context 트랜잭션과 AI State 동기화

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Context 생성·삭제 트랜잭션에서 Spring이 `core.context`와 `ai.context_ai_state`를 어떻게 함께 갱신하는지, 그리고 **수정이 어떻게 이 둘의 조합으로 처리되는지** 정의합니다.

Context는 불변 엔티티입니다. 본문을 in-place로 UPDATE하지 않으며, 본문이 바뀌면 새 `context_id`를 발급합니다. 불변성 원칙과 그 근거는 공용 계약 `static/05_AI_설계.md` §4.2가 원본입니다.

핵심 불변식은 하나입니다.

```text
동일한 context_id는 항상 동일한 Context 본문을 의미한다.
```

이 문서는 다음 세 절로 구성됩니다.

```text
3. 생성 동기화
4. 삭제 동기화
5. 수정 = 삭제 동기화 + 생성 동기화
```

상태값 집합과 전이 규칙, 결과 저장 불변식은 공용 계약(§6)이 원본입니다.

## 2. 두 스키마를 한 트랜잭션에서 쓰는 이유

`core`와 `ai`는 같은 PostgreSQL 인스턴스의 서로 다른 스키마입니다. 따라서 Spring은 두 스키마를 **단일 로컬 트랜잭션**으로 묶을 수 있습니다. 분산 트랜잭션이나 별도 Outbox가 필요하지 않습니다.

이 성질 덕분에 "Core는 저장됐는데 AI State가 없어서 영원히 처리되지 않는 Context"가 구조적으로 생기지 않습니다. 커밋되면 Core 데이터와 PENDING 상태가 항상 함께 존재합니다.

수정에서도 같은 성질이 그대로 쓰입니다. 구 Context 삭제와 신 Context 생성이 한 커밋에 들어가므로 "구 Context는 지워졌는데 신 Context가 없는" 중간 상태가 외부에 노출되지 않습니다.

`ai.context_ai_state`에 대한 Spring의 쓰기는 상태 초기화·취소·재시도 관리에 한정됩니다. 처리 진행에 따른 전이(PROCESSING, COMPLETED, 작업 중 오류 FAILED)는 FastAPI가 수행합니다. 상세는 [8장](#8-state-쓰기-책임)을 참조합니다.

## 3. 생성 동기화

### 3.1 순서

```text
@Transactional
  Record 확보 (신규 생성 또는 기존 활성 Record 조회)
  → core.context INSERT
       body
       → 새 context_id 발급
  → ai.context_ai_state INSERT
       context_id       = 새 context_id
       embedding_status = PENDING
       keyword_status   = PENDING
       retry_count      = 0
       updated_at       = now()
  → ContextAiRequested 이벤트 발행
커밋
→ (AFTER_COMMIT, 비동기) 새 context_id로 FastAPI 호출
```

### 3.2 규칙

- AI State는 항상 **새 Row INSERT**입니다. 기존 Row를 재사용하거나 되돌리는 경로는 없습니다.
- `retry_count`는 `0`에서 시작합니다. 재시도 예산은 `context_id` 단위이며, 새 Context는 언제나 3회의 기회를 새로 가집니다.
- Record 생성 트랜잭션(첫 Context 포함)도 동일합니다. Record와 첫 Context, AI State가 한 커밋에 들어갑니다.
- Record는 커밋 즉시 활성입니다. AI 완료를 기다리지 않습니다.
- 이 시점에 Keyword는 비어 있습니다. 조회 API는 이를 정상으로 취급합니다. 상세는 [`ai-response-assembly.md`](ai-response-assembly.md)를 참조합니다.

## 4. 삭제 동기화

### 4.1 순서

```text
@Transactional
  core.context.deleted_at = now()
  → ai.context_embedding
       is_deleted = true
       updated_at = now()
  → ai.context_ai_state
       embedding_status = CANCELLED
       keyword_status   = CANCELLED
       updated_at       = now()
커밋
```

### 4.2 규칙

- 두 status를 **모두** CANCELLED로 바꿉니다. 한쪽만 바꾸면 남은 단계가 재스캔 후보로 잡힙니다.
- 이전 상태와 무관하게 전이합니다. PENDING / PROCESSING / COMPLETED / FAILED 어디서든 CANCELLED가 됩니다.
- Embedding Row가 아직 없어 `is_deleted` UPDATE의 영향 행 수가 0이어도 **정상입니다.** 이 경우 늦게 도착하는 INSERT는 State의 CANCELLED가 차단합니다.
- FastAPI에 취소 신호를 보내지 않습니다. FastAPI가 저장 직전 status 검사에서 스스로 폐기합니다.

Context 삭제, Record 삭제, 회원 탈퇴의 구체적 절차와 벌크 처리는 [`deletion-cancellation.md`](deletion-cancellation.md)가 원본입니다. 여기서는 수정 경로가 재사용하는 최소 단위만 정의합니다.

## 5. 수정 = 삭제 동기화 + 생성 동기화

**별도의 Context UPDATE 경로를 만들지 않습니다.** 수정은 3장과 4장을 한 트랜잭션 안에서 이어 붙인 것입니다.

### 5.1 순서

```text
@Transactional
  구 core.context 조회 및 잠금 (SELECT ... FOR UPDATE)
  → 소유권 확인
  → [생성 동기화]  ← 반드시 먼저
       신 core.context INSERT   → 새 context_id 발급
       신 ai.context_ai_state INSERT
           embedding_status = PENDING
           keyword_status   = PENDING
           retry_count      = 0
           updated_at       = now()
  → [삭제 동기화]
       구 core.context.deleted_at = now()
       구 ai.context_ai_state.embedding_status = CANCELLED
       구 ai.context_ai_state.keyword_status   = CANCELLED
       존재하는 구 ai.context_embedding.is_deleted = true
       updated_at 갱신
  → ContextAiRequested(신 context_id) 이벤트 발행
커밋
→ (AFTER_COMMIT, 비동기) 신 context_id로 FastAPI 호출
```

**생성이 삭제보다 먼저 와야 합니다.** 활성 Record는 활성 Context를 최소 한 개 가져야 하고, 이를 위해 마지막 Context의 개별 삭제를 거부하는 가드가 있습니다(`deletion-cancellation.md` 4장). 삭제를 먼저 수행하면 Context가 하나뿐인 Record에서 이 가드에 걸려 정상적인 수정이 거부됩니다.

순서를 뒤집으면 활성 Context 수가 0이 되는 순간이 없으므로 가드를 그대로 통과합니다. **가드를 수정 경로에서 예외 처리하지 않습니다.** 안전 장치를 우회하는 대신 순서로 만족시킵니다.

트랜잭션 중간에 구·신 Context가 함께 활성인 구간이 생기지만 커밋 전이므로 외부에 관측되지 않습니다.

### 5.2 승계 금지

구 Context의 AI 상태와 파생 데이터는 신 Context로 **승계하지 않습니다.**

- Embedding
- Keyword
- Keyword Analysis
- AI State
- `retry_count`
- COMPLETED / FAILED 상태

구 Context의 Embedding이 이미 COMPLETED였더라도 신 Context에서 재사용하지 않습니다. 신 `context_id`는 새 처리 단위이므로 Embedding과 Keyword 판정을 처음부터 다시 수행합니다.

### 5.3 존재하지 않는 전이

기존 State를 다시 살리는 경로는 존재하지 않습니다.

- **"수정 시 기존 State를 PENDING으로 초기화"하는 전이는 없습니다.**
- COMPLETED를 PENDING으로 되돌리지 않습니다.
- FAILED를 PENDING으로 되돌리지 않습니다. FAILED는 진짜 종결 상태입니다.
- PROCESSING을 PENDING으로 덮어쓰지 않습니다. 구 State는 CANCELLED가 될 뿐입니다.

FAILED로 종결된 Context를 사용자가 수정하면, 구 Context는 CANCELLED가 되고 신 Context가 새 `context_id`와 새 `retry_count = 0`으로 처음부터 처리됩니다. 결과적으로 "실패한 Context를 수정하면 다시 처리된다"는 사용자 경험은 유지되지만, 그것은 상태 리셋이 아니라 **새 Context 생성**을 통해 달성됩니다.

### 5.4 본문 동일성 판정

본문이 실제로 바뀌었는지 여부는 여전히 판정해야 합니다. 바뀌지 않았다면 새 Context를 만들 이유가 없기 때문입니다.

- 저장 시 적용하는 정규화(앞뒤 공백 제거 등)를 **먼저 적용한 뒤** 비교합니다. 그러지 않으면 공백만 다른 요청이 불필요한 Context 교체와 AI 재처리를 유발합니다.
- 정규화 후 본문이 동일하면 **아무 것도 하지 않고 종료**합니다. 구 Context를 유지하고 AI State를 건드리지 않으며 FastAPI를 호출하지 않습니다. 이때 응답의 `contextId`는 기존 값 그대로입니다.
- 본문 외 필드만 바뀐 수정도 마찬가지로 Context를 교체하지 않습니다.
- 판정은 `replaceBody(String newBody)` 같은 도메인 메서드 한 곳에서만 수행합니다. 비교 로직이 여러 곳에 흩어지면 조건이 어긋납니다.

`core.context`에 단조 증가 `body_version` 컬럼을 둘지 여부는 back의 자체 판단 사항입니다. **AI 연동은 이 컬럼에 의존하지 않습니다.** AI 파생 데이터의 정체성은 전적으로 `context_id`가 담당하며, AI State·Embedding·Keyword 어디에도 Context 본문 버전 컬럼을 두지 않습니다.

## 6. 수정 트랜잭션 원칙

구 Context 삭제와 신 Context 생성은 **하나의 Spring Core 트랜잭션**에서 처리합니다. 공용 계약 `static/05_AI_설계.md` §5.5가 원본입니다.

FastAPI 호출은 이 트랜잭션에 포함하지 않습니다. `@TransactionalEventListener(AFTER_COMMIT)` 경로를 그대로 사용합니다. 상세는 [`ai-integration.md`](ai-integration.md) 4장을 참조합니다.

| 상황 | 결과 |
|---|---|
| 트랜잭션 실패 | 구 Context 삭제와 신 Context 생성이 **모두 롤백**. 구 Context와 구 AI State가 수정 이전 상태로 남음 |
| 트랜잭션 성공, FastAPI 호출 실패 | 신 Context와 신 PENDING State 유지. Scheduler가 복구 |

두 번째 행이 이 설계의 요점입니다. 커밋이 끝난 시점에 이미 신 `context_id`와 PENDING State가 DB에 있으므로, FastAPI 호출이 나가지 못해도 유실이 아닙니다. PENDING 만료(5분) 후 재스캔이 같은 Context를 집습니다. 상세는 [`ai-rescan-scheduler.md`](ai-rescan-scheduler.md)를 참조합니다.

부분 커밋은 허용하지 않습니다. "구 Context만 삭제되고 신 Context가 없는" 상태나 "신 Context는 있는데 구 State가 CANCELLED가 아닌" 상태가 만들어지면 안 되므로, 삭제와 생성을 두 트랜잭션으로 나누지 않습니다.

## 7. API 응답 계약

외부 API는 수정 Endpoint 형태(`PATCH`/`PUT`)를 유지할 수 있습니다. 다만 응답 계약에는 제약이 있습니다.

- **수정 응답은 새 `contextId`를 반환해야 합니다.**
- 구 `contextId`를 그대로 반환하는 응답 계약은 **허용하지 않습니다.** 구 `contextId`는 이미 삭제되고 CANCELLED된 식별자이므로, 클라이언트가 그 값으로 후속 조회나 삭제를 시도하면 실패합니다.
- 본문이 실제로 바뀌지 않아 교체가 일어나지 않은 경우에는 기존 `contextId`를 반환합니다. 클라이언트는 두 경우를 구분할 필요 없이 **응답에 실린 `contextId`로 자신의 상태를 갱신**하면 됩니다.
- Record 상세 등 Context 목록을 포함하는 응답도 같은 원칙을 따릅니다. 클라이언트가 캐시한 구 `contextId`를 계속 신뢰하게 만드는 응답을 내보내지 않습니다.

Client API 명세 자체는 `draft/08_API_명세.md`가 원본이며, 이 절은 AI 연동 관점에서 지켜야 할 제약을 정의합니다.

## 8. State 쓰기 책임

공용 계약 `static/05_AI_설계.md` §6.4의 표가 원본이며, back 구현은 이 표를 그대로 따릅니다.

| 상태 변경 | Spring | FastAPI |
|---|:---:|:---:|
| AI State 최초 생성 | O | X |
| `PENDING` 생성 | O | X |
| `PROCESSING` | X | O |
| `COMPLETED` | X | O |
| 작업 중 명시적 오류 `FAILED` | X | O |
| 재시도 소진 Finalizer `FAILED` | O | X |
| `CANCELLED` | O | X |
| `retry_count` | O | X |
| `is_deleted` | O | X |

구현상 반드시 지켜야 할 두 가지:

- **Spring은 FastAPI 작업 중 오류를 대신 판단해 FAILED로 쓰지 않습니다.** FastAPI 호출이 타임아웃되거나 `5xx`를 반환해도 Spring은 상태를 건드리지 않고 PENDING으로 둡니다. Spring이 쓰는 유일한 FAILED는 `retry_count` 소진 Finalizer뿐입니다.
- Spring은 PROCESSING과 COMPLETED를 쓰지 않습니다. 재스캔에서도 PROCESSING을 PENDING으로 되돌리지 않고, 만료된 PROCESSING을 그대로 둔 채 재요청합니다. FastAPI의 조건부 UPDATE가 `PROCESSING`을 허용 조건에 포함하므로 stale 작업이 재개됩니다.

양쪽이 같은 실패 사유로 FAILED를 각각 기록하면 실패 원인 추적이 불가능해집니다. 책임 경계를 코드 리뷰 항목으로 고정합니다.

## 9. 진행 중 수정과 경합

FastAPI가 구 Context를 처리하는 도중 사용자가 수정할 수 있습니다. Spring은 FastAPI 작업을 중단시키지 않으며, 중단시킬 필요도 없습니다.

```text
FastAPI: 구 context_id PROCESSING
Spring:  수정 트랜잭션
         신 Context INSERT → 신 context_id
         신 State PENDING
         구 Context 소프트 삭제
         구 State 두 status CANCELLED
         구 Embedding is_deleted = true
FastAPI: 구 결과 저장 시도
         대상 단계 status가 PROCESSING이 아님 (CANCELLED)  → 폐기
Spring:  신 context_id로 FastAPI 호출 → 독립 처리
```

- 수정 경합은 **삭제 경합과 동일한 경로**로 흡수됩니다. 수정 전용 경합 방어 로직을 별도로 만들지 않습니다.
- 폐기 판단은 FastAPI가 저장 직전에 수행합니다. Spring이 별도 취소 신호를 보내지 않습니다.
- 두 Context는 서로 다른 `context_id`이므로 State Row가 겹치지 않습니다. 구 작업의 결과가 신 State를 오염시킬 경로 자체가 없습니다.
- 수정 직후 짧은 구간에서 해당 Record는 Keyword 없이 응답될 수 있습니다. 신 Context가 아직 처리 전이기 때문이며, 이는 [`ai-response-assembly.md`](ai-response-assembly.md) 5장의 "AI 미완료" 케이스와 동일하게 다룹니다.

이 설계는 방어를 약화한 것이 아니라 **방어해야 할 동일 ID의 가변 상태 자체를 제거한 것**입니다.

## 10. AI 스키마 컬럼 (Migration 소유)

`ai` 스키마를 포함한 DB Migration의 실행 주체는 back입니다. 각 테이블의 역할 정의는 공용 계약 §12가 원본이며, 여기서는 migration이 만들어야 할 핵심 컬럼만 확정합니다.

| 테이블 | Primary Key | 핵심 컬럼 |
|---|---|---|
| `ai.context_ai_state` | `context_id` | `embedding_status`, `keyword_status`, `retry_count`, `updated_at` |
| `ai.context_embedding` | `context_id` | `user_id`, `record_id`, `embedding`, `embedding_profile`, `is_deleted`, `updated_at` |
| `ai.context_keyword` | `context_id` + `keyword_id` | `confidence`, `preset_version` |
| `ai.context_keyword_analysis` | `context_id` | `preset_version`, `unmatched_concepts`, `model_profile`, `updated_at` |
| `ai.keyword_preset` | `id` | `code`, `display_name`, `category`, `description`, `examples`, `embedding`, `embedding_profile`, `visibility`, `is_active`, `version` |

- **AI 테이블에는 Context 본문 버전 컬럼을 두지 않습니다.** `context_version` 컬럼은 어느 테이블에도 존재하지 않습니다.
- 남는 버전 개념은 세 가지뿐이며 모두 Context와 무관합니다. `embedding_profile`(모델·차원·거리 식별), `preset_version`(판정 시점 Preset 개정 번호), `keyword_preset.version`(Preset 목록 개정 번호).
- `context_id`가 본문 정체성을 담당하므로, 파생 데이터의 유효성 판정에는 별도 버전 비교 대신 `context_ai_state`의 status만 사용합니다.

## 11. 구현 주의점

- `ai.context_ai_state`는 JPA 엔티티로 매핑하되, 상태 전이 UPDATE는 조건절이 필요하므로 JPQL/네이티브 UPDATE로 작성합니다. 더티 체킹에 맡기면 조건부 갱신을 표현할 수 없습니다.
- Context 엔티티에는 본문 setter를 열지 않습니다. 본문 변경 요청은 반드시 "구 Context 삭제 + 신 Context 생성" 서비스 메서드를 거치게 해서, in-place UPDATE가 물리적으로 불가능하도록 만듭니다.
- 수정 트랜잭션 진입 시 구 `core.context` 행을 `SELECT ... FOR UPDATE`로 잠급니다. 동시 수정 요청 두 건이 각각 신 Context를 만들어 두 개가 살아남는 것을 막습니다. 잠금 획득 후 이미 `deleted_at IS NOT NULL`이면 요청을 거부합니다.
- `core`와 `ai` 사이에 물리 FK를 만들지 않습니다. `context_ai_state.context_id`는 값 참조입니다.
- 상태를 바꾸는 모든 경로에서 `updated_at`을 갱신합니다. 재스캔 만료 판정이 이 컬럼에 의존하므로, 누락되면 이미 처리 중인 작업이 계속 재스캔 후보로 잡힙니다.
- Migration은 back이 실행합니다. `ai` 스키마 DDL도 back 저장소의 migration이 원본입니다. `ddl-auto`는 `validate`로 둡니다.
