# Context 트랜잭션과 AI State 동기화

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Context 생성·수정 트랜잭션에서 Spring이 `core.context.body_version`과 `ai.context_ai_state`를 어떻게 함께 갱신하는지 정의합니다.

상태값 집합과 전이 규칙, 결과 저장 불변식은 공용 계약이 원본입니다.

## 2. 두 스키마를 한 트랜잭션에서 쓰는 이유

`core`와 `ai`는 같은 PostgreSQL 인스턴스의 서로 다른 스키마입니다. 따라서 Spring은 두 스키마를 **단일 로컬 트랜잭션**으로 묶을 수 있습니다. 분산 트랜잭션이나 별도 Outbox가 필요하지 않습니다.

이 성질 덕분에 "Core는 저장됐는데 AI State가 없어서 영원히 처리되지 않는 Context"가 구조적으로 생기지 않습니다. 커밋되면 Core 데이터와 PENDING 상태가 항상 함께 존재합니다.

`ai.context_ai_state`에 대한 쓰기는 Spring이 담당하는 상태 초기화·취소·재시도 관리에 한정됩니다. 처리 진행에 따른 전이(PROCESSING, COMPLETED, FAILED)는 FastAPI가 수행합니다.

## 3. Context 생성

### 3.1 순서

```text
@Transactional
  Record 확보 (신규 생성 또는 기존 활성 Record 조회)
  → core.context INSERT
       body
       body_version = 1
  → ai.context_ai_state INSERT
       context_id       = 새 Context id
       context_version  = 1
       embedding_status = PENDING
       keyword_status   = PENDING
       retry_count      = 0
       updated_at       = now()
  → ContextAiRequested 이벤트 발행
커밋
→ (AFTER_COMMIT, 비동기) FastAPI 호출
```

### 3.2 규칙

- `body_version`의 최초값은 `1`입니다. `core.context`의 컬럼 기본값으로 두고 애플리케이션에서 계산하지 않습니다.
- `context_ai_state.context_version`은 `body_version`의 **복제값**입니다. 독립적으로 증가시키지 않습니다.
- Record 생성 트랜잭션(첫 Context 포함)도 동일합니다. Record와 첫 Context, AI State가 한 커밋에 들어갑니다.
- Record는 커밋 즉시 활성입니다. AI 완료를 기다리지 않습니다.
- 이 시점에 Keyword는 비어 있습니다. 조회 API는 이를 정상으로 취급합니다. 상세는 [`ai-response-assembly.md`](ai-response-assembly.md)를 참조합니다.

## 4. Context 수정

### 4.1 순서

```text
@Transactional
  core.context 행 잠금 (SELECT ... FOR UPDATE)
  → 정규화한 새 body와 기존 body 비교
  → 동일하면: 아무 것도 하지 않고 종료 (AI 재처리 없음)
  → 다르면:
       body 갱신
       body_version = body_version + 1
       updated_at   = now()
       ai.context_ai_state UPDATE
           context_version  = 갱신된 body_version
           embedding_status = PENDING
           keyword_status   = PENDING
           retry_count      = 0
           updated_at       = now()
       ContextAiRequested 이벤트 발행
커밋
→ (AFTER_COMMIT, 비동기) FastAPI 호출
```

### 4.2 `body_version` 증가 조건

`body_version`은 **본문이 실제로 바뀔 때만** 증가합니다.

- 비교는 저장 직전 값 기준으로 수행합니다. 앞뒤 공백 제거 등 저장 시 적용하는 정규화를 **먼저 적용한 뒤** 비교합니다. 그러지 않으면 공백만 다른 수정이 재처리를 유발합니다.
- 본문 외 필드만 바뀐 수정은 증가시키지 않으며 AI State도 건드리지 않습니다.
- JPA `@Version`(낙관적 락)을 이 용도로 사용하지 않습니다. `@Version`은 어느 컬럼이 바뀌어도 증가하므로 본문 변경 판별 기준이 될 수 없습니다. 동시성 제어가 필요하면 별도 컬럼을 추가하고 `body_version`과 분리합니다.
- 엔티티 필드에 setter를 열지 않고 `updateBody(String newBody)` 같은 도메인 메서드 안에서만 비교와 증가를 수행합니다. 증가 로직이 여러 곳에 흩어지면 조건이 어긋납니다.

### 4.3 AI State 리셋

수정으로 인한 리셋은 이전 상태와 무관하게 무조건 PENDING입니다.

```text
PENDING / PROCESSING / COMPLETED / FAILED → PENDING
```

- COMPLETED였던 단계도 PENDING으로 되돌립니다. 본문이 바뀌었으므로 기존 결과는 구버전입니다.
- FAILED였던 단계도 PENDING이 됩니다. 공용 계약상 FAILED가 다시 살아나는 유일한 경로가 이것입니다.
- CANCELLED는 예외입니다. 삭제된 Context는 수정 대상이 될 수 없으므로, CANCELLED 상태에서 이 경로에 진입하면 버그입니다. 리셋 UPDATE의 조건절에 `embedding_status <> 'CANCELLED'`를 넣어 방어하고, 영향 행 수가 0이면 경고 로그를 남깁니다.

### 4.4 `retry_count = 0` 리셋

수정 시 `retry_count`를 0으로 되돌립니다.

이유는 재시도 예산이 **처리 시도 단위가 아니라 Context Version 단위**이기 때문입니다. v1에서 3회를 소진해 FAILED로 종결된 Context가 v2로 수정되면, v2는 완전히 새로운 입력이므로 다시 3회의 기회를 가져야 합니다. 리셋하지 않으면 한 번 실패한 Context가 수정 후에도 영구히 처리되지 못합니다.

## 5. 진행 중 수정과 경합

FastAPI가 v1을 처리하는 도중 Spring이 v2로 수정할 수 있습니다. Spring은 FastAPI 작업을 중단시키지 않으며, 중단시킬 필요도 없습니다.

```text
FastAPI: v1 PROCESSING
Spring:  body 수정 → body_version = 2
         state.context_version = 2
         embedding_status = PENDING (PROCESSING을 덮어씀)
FastAPI: v1 결과 저장 시도
         request.contextVersion(1) != state.context_version(2)  → 폐기
         대상 단계 status가 PROCESSING이 아님             → 폐기
```

- Spring은 PROCESSING을 PENDING으로 **덮어씁니다**. PROCESSING을 존중해 기다리면 v2 처리가 시작되지 않습니다.
- 폐기 판단은 FastAPI가 저장 직전에 수행합니다. Spring이 별도 취소 신호를 보내지 않습니다.
- 이 구간에서 해당 Context는 검색과 Keyword 응답에서 일시적으로 제외될 수 있습니다. 구버전 결과를 노출하지 않기 위한 의도된 동작입니다.

## 6. 조회 시 Version 일치 확인

Keyword를 응답에 실을 때는 상태만이 아니라 Version 일치도 확인합니다.

```text
ck.context_version = ct.body_version
AND state.keyword_status = 'COMPLETED'
AND state.context_version = ct.body_version
```

`keyword_status = COMPLETED`만 보면 v1 완료 직후 v2로 수정된 찰나에 구 Keyword가 노출될 수 있습니다. Version 비교가 그 창을 닫습니다. 비교는 `<`가 아니라 `!=` 기준으로 판단합니다.

## 7. 구현 주의점

- `ai.context_ai_state`는 JPA 엔티티로 매핑하되, 상태 전이 UPDATE는 조건절이 필요하므로 JPQL/네이티브 UPDATE로 작성합니다. 더티 체킹에 맡기면 조건부 갱신을 표현할 수 없습니다.
- `core`와 `ai` 사이에 물리 FK를 만들지 않습니다. `context_ai_state.context_id`는 값 참조입니다.
- 상태를 바꾸는 모든 경로에서 `updated_at`을 갱신합니다. 재스캔 만료 판정이 이 컬럼에 의존하므로, 누락되면 이미 처리 중인 작업이 계속 재스캔 후보로 잡힙니다.
- Migration은 back이 실행합니다. `ai` 스키마 DDL도 back 저장소의 migration이 원본입니다. `ddl-auto`는 `validate`로 둡니다.
