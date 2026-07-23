# 삭제와 취소 처리

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Context 삭제, Record 삭제, 회원 탈퇴, 그리고 **Context 수정으로 인한 구 Context 취소** 시 Spring이 AI 파생 데이터를 어떻게 처리하는지 정의합니다.

Core 도메인의 소프트 삭제 절차(활성 Context 수 확인, 마지막 Record였던 Collection 자동 삭제, `record_count` 갱신 등)는 데이터 모델 문서가 원본입니다. 여기서는 그 트랜잭션에 **추가로 붙는 AI 처리**만 다룹니다.

Context는 불변 엔티티이므로 수정은 구 Context 삭제와 신 Context 생성의 조합입니다. 그 결과 **삭제와 수정은 완전히 동일한 취소 경로를 사용합니다.** 수정 전용 취소 로직이나 수정 전용 상태 전이는 존재하지 않으며, 이 문서의 3장 공통 처리가 두 경우 모두에 그대로 적용됩니다. 수정 트랜잭션의 전체 순서는 [`context-state-sync.md`](context-state-sync.md) 5장이 원본입니다.

## 2. 두 장치의 역할 분리

AI 파생 데이터의 무효화는 두 장치가 담당하며 서로를 대체하지 않습니다.

| 장치 | 역할 |
|---|---|
| AI State `CANCELLED` | 진행 중·예정 작업의 **실시간 취소**. 처리·저장·재스캔 차단 |
| `ai.context_embedding.is_deleted = true` | **검색 제외** 보조 방어선, 파생 데이터 삭제 마커, 향후 물리 삭제 대상 식별 |

CANCELLED만 있으면 이미 저장된 Embedding이 검색 쿼리에 남습니다. `is_deleted`만 있으면 진행 중인 작업이 계속 돌다가 결과를 저장하려 시도합니다. 둘 다 필요합니다.

## 3. 공통 처리

삭제 계열 트랜잭션은 모두 다음을 **같은 트랜잭션 안에서** 수행합니다.

```text
core 소프트 삭제 (deleted_at = now())
→ ai.context_embedding
     is_deleted = true
     updated_at = now()
→ ai.context_ai_state
     embedding_status = CANCELLED
     keyword_status   = CANCELLED
     updated_at       = now()
커밋
```

규칙:

- 두 status를 **모두** CANCELLED로 바꿉니다. 한쪽만 바꾸면 남은 단계가 재스캔 후보로 잡힙니다.
- 이전 상태와 무관하게 전이합니다. PENDING / PROCESSING / COMPLETED / FAILED 어디서든 CANCELLED가 됩니다.
- 상태 변경이므로 `updated_at`을 반드시 갱신합니다. 누락하면 만료 판정과 운영 조회에서 삭제 시점을 알 수 없습니다.
- FastAPI에 취소 신호를 보내지 않습니다. 취소 통보용 내부 API를 두지 않습니다. FastAPI는 저장 직전 status 검사에서 스스로 폐기합니다.
- 대상 Context가 여러 건이면 `context_id IN (...)` 형태의 벌크 UPDATE로 처리합니다. 건별 반복은 Record 삭제·탈퇴에서 쿼리 수가 폭발합니다.
- **`is_deleted` UPDATE의 영향 행 수가 0이어도 정상입니다.** AI 처리가 아직 Embedding을 저장하기 전이면 걸 대상이 되는 Row 자체가 없습니다. 이를 오류로 처리하거나 경고 로그를 남기지 않습니다. 이 경우 늦게 도착하는 Embedding INSERT는 State의 CANCELLED가 차단합니다.

마지막 항목이 두 장치가 서로를 대체하지 않는 이유를 가장 잘 보여줍니다. `is_deleted`는 이미 존재하는 Row에만 걸 수 있고, 아직 존재하지 않는 Row에 대한 방어는 CANCELLED만이 수행합니다.

`ai.context_ai_state` Row가 없는 경우는 다릅니다. Core Context와 AI State는 같은 트랜잭션에서 INSERT되므로 활성 Context에 State가 없다면 정합성 문제이며, 이때는 경고 로그를 남깁니다.

## 4. Context 삭제

```text
@Transactional
  record 행 잠금
  → 활성 Context 수 확인
  → 1개면 거부 (마지막 Context는 개별 삭제 불가)
  → 2개 이상이면
       core.context.deleted_at = now()
       ai.context_embedding: is_deleted = true, updated_at = now()
       ai.context_ai_state:  두 status CANCELLED, updated_at = now()
커밋
```

프런트엔드가 Record 삭제를 안내하더라도, 백엔드는 프런트엔드 동작과 무관하게 마지막 Context의 개별 삭제를 거부합니다.

### 4.1 수정으로 인한 구 Context 취소

Context 수정은 위 취소 절차를 구 Context에 그대로 적용한 뒤, 같은 트랜잭션에서 신 Context 생성을 이어 붙인 것입니다.

```text
@Transactional
  구 core.context 행 잠금
  → [이 문서 3장의 공통 처리를 구 Context에 적용]
  → 신 Context INSERT + 신 AI State PENDING
  → 구 Context 취소
       core.context.deleted_at = now()
       ai.context_embedding: is_deleted = true
       ai.context_ai_state:  두 status CANCELLED
커밋
```

삭제와 다른 점은 **신 Context INSERT가 구 Context 삭제보다 먼저 온다는 것**입니다.

이 순서 덕분에 활성 Context 수가 한 번도 0이 되지 않으므로, "마지막 Context는 개별 삭제 불가" 가드를 그대로 통과합니다. 반대 순서로 구현하면 Context가 하나뿐인 Record에서 가드에 걸려 수정이 거부됩니다. 가드를 수정 경로에서 예외 처리하는 방식은 사용하지 않습니다. 순서로 해결하는 편이 안전 장치를 우회하지 않으면서 목적을 달성합니다.

AI 관점에서 구 Context는 삭제된 Context와 완전히 동일하게 취급됩니다.

- 재스캔 대상이 아닙니다 (CANCELLED).
- 늦게 도착한 구 Context 결과는 저장되지 않습니다.
- 검색과 Keyword 응답에서 제외됩니다.
- Finalizer가 CANCELLED를 FAILED로 덮어쓰지 않습니다.

신 Context는 새 `context_id`를 가진 독립 처리 단위이며, 구 Context의 AI 상태나 파생 데이터를 승계하지 않습니다.

## 5. Record 삭제

Record 삭제는 그 Record의 **모든 활성 Context**를 삭제하므로 AI 처리 대상도 복수입니다.

```text
@Transactional
  record 행 잠금
  → 포함된 활성 Collection 역조회, 마지막인 Collection 식별
  → record.deleted_at = now()
  → 활성 context 전체 deleted_at = now()   → contextIds 확보
  → collection_record 연결 소프트 삭제
  → 마지막 Record였던 Collection 소프트 삭제
  → 남은 Collection의 record_count 갱신
  → ai.context_embedding  WHERE context_id IN (contextIds)
        is_deleted = true, updated_at = now()
  → ai.context_ai_state   WHERE context_id IN (contextIds)
        두 status CANCELLED, updated_at = now()
커밋
```

`contextIds`는 소프트 삭제 UPDATE의 `RETURNING id` 또는 삭제 직전 SELECT로 확보합니다. 삭제 후에 다시 조회하면 활성 필터에 걸려 빈 결과가 나오므로 순서에 주의합니다.

## 6. 회원 탈퇴

탈퇴 User의 AI 파생 데이터는 즉시 검색·공개 대상에서 제외합니다.

```text
@Transactional
  member.deleted_at = now()
  → social_account 소프트 삭제 + provider_user_id 마스킹
  → record / context 소프트 삭제
  → collection / collection_record 소프트 삭제
  → 해당 User가 생성한 follow, 해당 User를 대상으로 하는 follow 소프트 삭제
  → ai.context_embedding  WHERE user_id = :memberId
        is_deleted = true, updated_at = now()
  → ai.context_ai_state   WHERE context_id IN (해당 User의 context id)
        두 status CANCELLED, updated_at = now()
커밋
```

- `ai.context_embedding`은 `user_id` 비정규화 컬럼을 가지므로 Context id 목록 없이 한 번에 처리할 수 있습니다.
- `ai.context_ai_state`에는 `user_id`가 없으므로 `core.context`에서 id 목록을 얻어 처리합니다. 데이터량이 많으면 청크 단위로 나눕니다.
- 데이터량이 커서 탈퇴 처리를 배치로 분리하더라도, Feed·Library 노출 제외는 `member.deleted_at`으로 즉시 반영됩니다. 조회 경로가 member 삭제 상태를 확인하므로 AI 정리 배치를 기다리지 않습니다.
- 보존 기간과 물리 삭제 시점은 별도 개인정보 정책에 따릅니다. 탈퇴 트랜잭션에서 물리 삭제하지 않습니다.

## 7. `is_deleted`를 Spring만 변경하는 이유

`ai.context_embedding.is_deleted`를 `false`로 바꾸거나 복원하는 주체는 존재하지 않습니다. 설정 주체는 Spring뿐입니다.

- 삭제 여부는 **Core 도메인 사실**입니다. Core를 소유한 Spring만이 그 사실을 압니다. FastAPI는 `core.*`에 접근하지 않으므로 판단 근거 자체가 없습니다.
- FastAPI의 결과 저장은 UPSERT입니다. UPSERT가 `is_deleted`를 함께 갱신하면, 삭제 직전에 시작된 작업이 늦게 도착하면서 삭제 마커를 지워버립니다. 그 결과 삭제된 Context가 검색에 다시 나타납니다.
- 따라서 FastAPI의 UPSERT는 `is_deleted` 컬럼을 **INSERT 시 기본값으로만 다루고 UPDATE 대상에서 제외**합니다.
- 삭제 데이터는 복구하지 않는다는 Core 정책과도 일치합니다. `true → false` 전이가 필요한 시나리오가 없습니다.

늦게 도착한 결과가 저장되지 않는 것은 status 검사가 보장합니다.

```text
FastAPI 처리 시작
→ Spring이 Context 삭제(또는 수정에 의한 교체), State CANCELLED
→ FastAPI 결과 도착
→ 저장 전 status 검사: PROCESSING 아님
→ 결과 폐기
```

수정으로 교체된 경우도 이 흐름 그대로입니다. 구 `context_id`의 State가 CANCELLED이므로 구 결과가 폐기되고, 신 `context_id`는 별도 State를 가지므로 영향을 받지 않습니다.

## 8. 조회·계산에서의 제외

CANCELLED와 `is_deleted`는 처리 경로뿐 아니라 모든 읽기 경로에서도 필터로 작동합니다.

| 경로 | 필터 |
|---|---|
| 개인 자연어 검색 | `is_deleted = false`, `embedding_status = COMPLETED`, `embedding_profile` 일치 |
| Keyword 응답 조립 | `keyword_status = COMPLETED`, Preset `active = true`, `context.deleted_at IS NULL` |
| Feed 특징 계산 | 위와 동일 + `collection`·`record`·`member` 활성 확인 |
| 재스캔 후보 | CANCELLED 제외 |
| Finalizer 후보 | CANCELLED 제외 (CANCELLED 우선) |

구 Context를 걸러내기 위한 별도 버전 비교 조건은 없습니다. `keyword_status = CANCELLED` 하나가 수정·삭제 양쪽을 모두 덮습니다.

Feed·Library 조회는 `member`, `collection`, `collection_record`, `record`의 삭제 상태를 모두 확인해야 합니다. 한 단계만 빠져도 삭제된 데이터가 노출됩니다.
