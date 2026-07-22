# 재스캔 Scheduler

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

유실되거나 멈춘 AI 처리를 복구하는 Spring Scheduler를 정의합니다. 별도 메시지 큐 없이 재처리가 가능한 이유는 AI State가 DB에 영속되기 때문이며, 이 Scheduler가 그 전제를 실제로 성립시키는 유일한 장치입니다.

## 2. 파라미터

| 항목 | 값 | 설정 키 |
|---|---|---|
| 실행 주기 | 5분 | `pinlog.ai.rescan.interval` |
| PENDING 만료 | 5분 | `pinlog.ai.rescan.pending-expiry` |
| PROCESSING 만료 | 10분 | `pinlog.ai.rescan.processing-expiry` |
| 최대 재시도 | 3회 | `pinlog.ai.rescan.max-retry` |
| 1회 배치 크기 | 100 | `pinlog.ai.rescan.batch-size` |
| Backoff | 없음 | — |

Backoff를 두지 않습니다. 실행 주기가 5분이므로 그 자체가 최소 간격 역할을 하고, 최대 3회이므로 지수 backoff의 이득이 없습니다.

PROCESSING 만료가 PENDING보다 긴 이유는 실제로 처리 중일 가능성을 고려하기 때문입니다. 10분은 Embedding + LLM 판정의 정상 소요 시간을 크게 웃도는 값이므로, 이 시간을 넘긴 PROCESSING은 프로세스 종료로 유실된 작업으로 간주합니다.

## 3. 실행 방식

```text
@Scheduled(fixedDelayString = "${pinlog.ai.rescan.interval}")
```

- `fixedRate`가 아니라 `fixedDelay`를 사용합니다. 이전 실행이 길어졌을 때 겹쳐 도는 것을 막습니다.
- `@EnableScheduling`을 두고 전용 `ThreadPoolTaskScheduler`를 사용합니다. 기본 단일 스레드 스케줄러를 다른 배치와 공유하지 않습니다.
- 애플리케이션 인스턴스가 여러 대여도 별도 리더 선출을 두지 않습니다. 후보 선택이 `FOR UPDATE SKIP LOCKED` 기반이므로 인스턴스끼리 서로 다른 행을 집습니다.

## 4. 후보 선택

### 4.1 조건

재스캔 대상:

- `embedding_status` 또는 `keyword_status`가 만료된 PENDING
- `embedding_status` 또는 `keyword_status`가 만료된 PROCESSING
- `retry_count < 3`

재스캔 비대상:

- 두 단계가 모두 COMPLETED
- FAILED
- CANCELLED

FAILED는 자동 재스캔 대상이 아닙니다. FAILED가 다시 처리되는 유일한 경로는 Context 본문 수정으로 Spring이 PENDING으로 초기화하는 것입니다.

만료 판정 기준 컬럼은 `updated_at`입니다.

### 4.2 잠금

```sql
SELECT s.context_id, s.context_version,
       s.embedding_status, s.keyword_status, s.retry_count
FROM ai.context_ai_state s
WHERE s.retry_count < :maxRetry
  AND (
        (s.embedding_status = 'PENDING'    AND s.updated_at < now() - :pendingExpiry)
     OR (s.keyword_status   = 'PENDING'    AND s.updated_at < now() - :pendingExpiry)
     OR (s.embedding_status = 'PROCESSING' AND s.updated_at < now() - :processingExpiry)
     OR (s.keyword_status   = 'PROCESSING' AND s.updated_at < now() - :processingExpiry)
  )
ORDER BY s.updated_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

`FOR UPDATE SKIP LOCKED`가 필요한 이유:

- 다중 인스턴스나 실행 겹침 상황에서 같은 Context를 두 번 집는 것을 막습니다.
- `SKIP LOCKED`이므로 잠긴 행을 기다리지 않고 건너뜁니다. 대기하면 배치 전체가 느린 한 행에 묶입니다.
- 이것이 중복 방어의 유일한 장치는 아닙니다. FastAPI의 PROCESSING 조건부 UPDATE가 최종 방어선이며, 두 장치는 서로를 대체하지 않습니다.

`ORDER BY updated_at`으로 가장 오래 멈춘 것부터 처리합니다.

## 5. 재시도 실행

선택한 각 Context에 대해:

```text
같은 트랜잭션 안에서
  retry_count = retry_count + 1
  만료된 PROCESSING 단계를 PENDING으로 되돌림
  updated_at = now()
커밋
→ 최신 core.context 재조회
→ FastAPI 호출
```

### 5.1 최신 Core Context 재조회

재시도 요청 본문은 **반드시 최신 Core Context를 다시 조회해서** 만듭니다. 이전 요청 본문을 보관했다가 재전송하지 않습니다.

이유:

- 첫 시도 이후 본문이 수정되어 `body_version`이 올라갔을 수 있습니다. 구버전 본문을 보내면 저장 직전 Version 비교에서 폐기되어 API 비용만 낭비합니다.
- 재조회 결과 Context가 이미 삭제(`deleted_at IS NOT NULL`)되었으면 호출하지 않고 건너뜁니다. 이때 상태는 CANCELLED여야 하며, 아니라면 정합성 경고 로그를 남깁니다.
- 요청에 싣는 `contextVersion`은 재조회한 `core.context.body_version`이며, `context_ai_state.context_version`이 아닙니다. 두 값이 다르면 Core가 최신이므로 State도 함께 맞춥니다.

### 5.2 후보 선택 후 삭제된 경우

후보를 잡은 뒤 호출 직전에 Context가 삭제될 수 있습니다. 이 경합은 두 지점에서 막힙니다.

- Spring: 재조회 시 삭제 확인 후 호출 생략
- FastAPI: 저장 직전 status 검사에서 CANCELLED이므로 저장 거부

호출이 나가버렸더라도 결과는 저장되지 않으므로 정합성이 깨지지 않습니다.

## 6. 재시도 종결 (Finalizer)

**`retry_count`가 최대치에 도달했을 때 미완료 단계를 FAILED로 종결하는 경로가 코드에 반드시 존재해야 합니다.** 이 경로가 없으면 `retry_count >= 3`인 행은 후보 조건에서 제외되기만 할 뿐 영원히 PENDING/PROCESSING 상태로 남아, 상태 지표상 "처리 중"으로 오인되고 관측이 불가능해집니다. 재스캔 루프의 부수 효과로 자연히 해결되지 않으므로 명시적으로 구현합니다.

### 6.1 실행

같은 Scheduler 실행 안에서 후보 처리 이후 별도 단계로 수행합니다.

```sql
SELECT context_id
FROM ai.context_ai_state
WHERE retry_count >= :maxRetry
  AND (embedding_status IN ('PENDING','PROCESSING')
       OR keyword_status IN ('PENDING','PROCESSING'))
ORDER BY updated_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

```sql
UPDATE ai.context_ai_state
SET embedding_status = CASE WHEN embedding_status IN ('PENDING','PROCESSING')
                            THEN 'FAILED' ELSE embedding_status END,
    keyword_status   = CASE WHEN keyword_status   IN ('PENDING','PROCESSING')
                            THEN 'FAILED' ELSE keyword_status END,
    updated_at = now()
WHERE context_id = :contextId;
```

### 6.2 규칙

- **미완료 단계만** FAILED로 바꿉니다. 이미 COMPLETED인 단계는 그대로 둡니다. 부분 성공을 지우면 재시도 시 Embedding을 다시 만들어야 하므로 부분 재사용의 이점이 사라집니다.
- CANCELLED는 건드리지 않습니다. CASE 조건이 이를 보장합니다.
- FAILED 전환도 상태 변경이므로 `updated_at`을 갱신합니다.
- FAILED 전환 시 `contextId`와 마지막 실패 사유를 로그로 남깁니다. Preset이나 모델 설정 문제를 판별할 유일한 단서입니다.

FAILED는 자동으로 되살아나지 않습니다. Context 본문이 수정되어 Spring이 PENDING으로 초기화할 때만 다시 처리됩니다.

## 7. 부분 재개와의 관계

Scheduler는 단계별 상태를 구분해서 다룹니다. 두 단계를 한 덩어리로 취급하지 않습니다.

```text
embedding_status = COMPLETED
keyword_status   = PENDING (만료)
→ 재스캔 대상
→ FastAPI는 기존 Embedding의 Version·Profile이 일치하면 재사용
→ Keyword 단계부터 재개, Embedding API 재호출 없음
```

재사용 판단은 FastAPI가 합니다. Spring은 "이 Context를 다시 처리해 달라"고만 요청하고 어느 단계부터 시작할지 지시하지 않습니다. 요청 자체가 상태 기반 멱등이므로 단계 지정이 불필요합니다.

## 8. 관찰 지점

- 회차별 후보 건수
- 회차별 FAILED 종결 건수
- `retry_count` 분포
- 상태별 잔량 (PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED)

후보 건수가 지속적으로 배치 크기에 붙어 있으면 FastAPI가 처리량을 못 따라가고 있다는 신호입니다.
