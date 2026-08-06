# 재스캔 Scheduler와 FAILED Finalizer

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

유실되거나 멈춘 AI 처리를 복구하는 Spring Scheduler를 정의합니다. 별도 메시지 큐 없이 재처리가 가능한 이유는 AI State가 DB에 영속되기 때문이며, 이 Scheduler가 그 전제를 실제로 성립시키는 유일한 장치입니다.

Scheduler는 두 개의 독립된 책임을 가지며 **둘 다 필수 구성 요소**입니다.

| 구성 요소 | 책임 | 절 |
|---|---|---|
| 재스캔 | `retry_count < 3`인 stale 작업을 다시 FastAPI에 요청 | 4~5장 |
| FAILED Finalizer | `retry_count >= 3`인 만료 작업의 미완료 단계를 FAILED로 종결 | 6장 |

Finalizer는 선택 사항이 아닙니다. 근거는 [6장](#6-failed-finalizer)에 있습니다.

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

### 3.1 처리 순서

한 회차 안에서 다음 순서를 지킵니다. 공용 계약 `static/05_AI_설계.md` §10.3의 순서와 동일합니다.

```text
1. retry_count >= 3 만료 작업 Finalize   (6장)
2. retry_count < 3 stale 작업 조회       (4장)
3. retry_count 증가 + updated_at 갱신
4. Commit
5. 최신 Context 확인
6. 삭제 여부 확인
7. FastAPI 호출                          (5장)
```

3단계에서 `updated_at`을 **반드시 함께** 갱신합니다. 스키마의 `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`는 INSERT 기본값이라 UPDATE를 자동으로 갱신해 주지 않으므로, UPDATE 문에 직접 써야 합니다([5장](#5-재시도-실행) SQL).

마지막 재시도의 실행 창을 실제로 만드는 것은 이 갱신과 Finalizer의 만료 조건입니다. 방금 `retry_count`를 3으로 올린 행은 `updated_at`이 현재 시각이 되어 만료 술어(`updated_at < now() - :expiry`)를 벗어나고, 그래서 같은 회차는 물론 만료 시간이 다시 지나기 전까지 Finalizer 후보 조건([6.1](#61-대상))에 걸리지 않습니다.

Finalize를 **먼저** 수행하는 순서는 그 위에 겹치는 심층 방어입니다. `updated_at` 갱신이 제대로 있는 한 1단계와 3단계를 맞바꿔도 관측되는 동작은 같습니다. 그럼에도 순서를 고정하는 이유는, 어떤 구현이 `updated_at` 갱신을 빠뜨렸을 때 순서마저 뒤집혀 있으면 같은 회차에서 방금 `retry_count`를 3으로 올린 행을 곧바로 FAILED로 종결해 마지막 재시도가 실행되기도 전에 사망 선고를 내리기 때문입니다. 그때 순서가 남아 있으면 3회차 요청이 최소 한 주기 동안 살아 있을 기회를 갖습니다. 두 장치는 서로를 대체하지 않고 서로를 받칩니다.

3~4단계와 5~7단계 사이에 커밋 경계가 있습니다. `retry_count` 증가는 커밋으로 확정하고, 외부 호출은 트랜잭션 밖에서 수행합니다. 호출 지연이 행 잠금을 붙잡지 않게 하기 위해서입니다.

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

FAILED는 자동 재스캔 대상이 아니며, 되살아나는 경로도 없습니다. Context는 불변이므로 "실패한 Context를 다시 처리한다"는 개념 자체가 존재하지 않습니다. 사용자가 본문을 수정하면 **새 `context_id`와 새 PENDING State**가 만들어지고, 그것이 별도의 재스캔 단위가 됩니다.

CANCELLED도 마찬가지로 대상이 아닙니다. Context가 수정으로 삭제되었다면 구 State는 이미 CANCELLED이므로 재스캔 후보에 잡히지 않습니다. 신 Context는 자기 자신의 PENDING State로 처리됩니다. 즉 **구 Context가 재스캔을 통해 되살아나는 경로는 없습니다.**

만료 판정 기준 컬럼은 `updated_at`입니다.

### 4.2 잠금

```sql
SELECT s.context_id,
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
  updated_at = now()
커밋
→ 해당 context_id의 core.context 재조회
→ 삭제 여부 확인
→ FastAPI 호출
```

`updated_at = now()`는 선택이 아닙니다. 이 갱신이 마지막 재시도의 실행 창을 만들며, 빠뜨리면 `retry_count`가 3이 된 행의 `updated_at`이 여전히 만료 시각에 머물러 바로 다음 회차의 Finalizer 첫 단계에 잡힙니다. 방금 보낸 재시도 요청이 아직 처리 중이어도 그렇습니다([3.1](#31-처리-순서) · [6.1](#61-대상)).

만료된 PROCESSING을 PENDING으로 되돌리지 않습니다. Spring은 PROCESSING을 쓰지도, 해제하지도 않습니다. FastAPI의 선점 UPDATE가 `PROCESSING`을 허용 조건에 포함하고 있으므로(공용 계약 §6.5), stale PROCESSING은 재요청만으로 재개됩니다. Spring이 상태를 손대면 두 주체가 같은 컬럼을 경쟁적으로 쓰게 되어 소유권 경계가 무너집니다.

### 5.1 Core Context 재조회

재시도 요청 본문은 **반드시 해당 `context_id`의 Core Context를 다시 조회해서** 만듭니다. 이전 요청 본문을 보관했다가 재전송하지 않습니다.

이유:

- Context는 불변이므로 재조회한 본문은 첫 시도와 동일합니다. 그럼에도 재조회하는 이유는 본문을 얻기 위해서가 아니라 **그 Context가 아직 살아 있는지 확인하기 위해서**입니다.
- 재조회 결과 Context가 이미 삭제(`deleted_at IS NOT NULL`)되었으면 호출하지 않고 건너뜁니다. 삭제 원인이 단순 삭제인지 수정으로 인한 교체인지는 구분할 필요가 없습니다. 두 경우 모두 이 `context_id`는 더 이상 처리 대상이 아닙니다.
- 이때 상태는 CANCELLED여야 하며, 아니라면 정합성 경고 로그를 남깁니다. Spring 삭제·수정 트랜잭션이 CANCELLED 기록을 빠뜨렸다는 뜻이기 때문입니다.
- 요청 payload에 Context 본문 버전 값을 싣지 않습니다. 필드 구성은 [`ai-integration.md`](ai-integration.md) 4.4를 참조합니다.

### 5.2 후보 선택 후 삭제된 경우

후보를 잡은 뒤 호출 직전에 Context가 삭제될 수 있습니다. 이 경합은 두 지점에서 막힙니다.

- Spring: 재조회 시 삭제 확인 후 호출 생략
- FastAPI: 저장 직전 status 검사에서 CANCELLED이므로 저장 거부

호출이 나가버렸더라도 결과는 저장되지 않으므로 정합성이 깨지지 않습니다.

## 6. FAILED Finalizer

Finalizer는 **선택 사항이 아니라 반드시 존재해야 하는 구성 요소**입니다. 공용 계약 `static/05_AI_설계.md` §10.4가 원본입니다.

근거는 내부 API 계약의 성질에 있습니다. `process` 호출의 응답은 `202 Accepted`이며 이는 접수만 뜻합니다. **`202` 이후 FastAPI 내부에서 일어난 실패를 Spring 호출부는 동기적으로 알 수 없습니다.** 완료 통보용 웹훅도 두지 않습니다. 따라서 Finalizer가 없으면 `retry_count >= 3`인 행은 재스캔 후보 조건(`retry_count < 3`)에서 제외되기만 할 뿐, **PROCESSING 상태로 영원히 남습니다.** 상태 지표상 "처리 중"으로 오인되어 장애 관측 자체가 불가능해집니다.

재스캔 루프의 부수 효과로 자연히 해결되지 않으므로 별도 경로로 명시적으로 구현합니다.

### 6.1 대상

```text
retry_count >= 3
AND 대상 단계 status IN ('PENDING', 'PROCESSING')
AND 대상 단계가 만료됨
```

만료 기준은 재스캔과 동일합니다. PENDING은 5분, PROCESSING은 10분이며 판정 컬럼은 `updated_at`입니다.

만료 조건이 없으면 `retry_count`를 3으로 올린 그 회차에서 곧바로 종결되어, 마지막 재시도 요청이 처리될 시간을 갖지 못합니다. 그 창을 확보하는 것은 이 만료 조건과 [3.1](#31-처리-순서) 3단계의 `updated_at` 갱신이 짝을 이룬 결과이고, 「Finalize를 먼저」 순서는 그 위에 겹치는 심층 방어입니다.

### 6.2 실행

Scheduler 회차의 **첫 단계**로 수행합니다(3.1 참조).

```sql
SELECT context_id, embedding_status, keyword_status
FROM ai.context_ai_state
WHERE retry_count >= :maxRetry
  AND (
        (embedding_status = 'PENDING'    AND updated_at < now() - :pendingExpiry)
     OR (keyword_status   = 'PENDING'    AND updated_at < now() - :pendingExpiry)
     OR (embedding_status = 'PROCESSING' AND updated_at < now() - :processingExpiry)
     OR (keyword_status   = 'PROCESSING' AND updated_at < now() - :processingExpiry)
  )
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

후보 잠금은 재스캔과 동일하게 `FOR UPDATE SKIP LOCKED`입니다. 다중 인스턴스에서 같은 행을 두 번 종결하지 않기 위해서입니다.

### 6.3 규칙

- **FastAPI를 호출하지 않습니다.** Finalizer는 순수한 상태 정리 단계입니다.
- **미완료 단계만** FAILED로 바꿉니다. 이미 COMPLETED인 단계는 그대로 둡니다. 부분 성공을 지우면 Embedding을 다시 만들어야 하므로 부분 재사용의 이점이 사라집니다.
- `COMPLETED`, `FAILED`, `CANCELLED`는 그대로 둡니다. CASE의 `IN ('PENDING','PROCESSING')` 화이트리스트가 이를 보장합니다.
- **CANCELLED가 우선합니다. Finalizer는 CANCELLED를 FAILED로 덮어쓸 수 없습니다.** 후보를 잡은 뒤 UPDATE 직전에 Context가 삭제되거나 수정으로 교체될 수 있으므로, 화이트리스트를 블랙리스트(`<> 'COMPLETED'` 등)로 바꾸지 않습니다. 블랙리스트로 쓰면 그 창에서 CANCELLED가 FAILED로 뒤집힙니다.
- FAILED 전환도 상태 변경이므로 `updated_at`을 갱신합니다.
- FAILED 전환 시 `contextId`와 마지막 실패 사유를 로그로 남깁니다. Preset이나 모델 설정 문제를 판별할 유일한 단서입니다.

### 6.4 예시

| 상태 | 결과 |
|---|---|
| `embedding COMPLETED` / `keyword PROCESSING` / retry 3 / keyword 만료 | embedding 유지, keyword `FAILED` |
| `embedding PROCESSING` / `keyword PENDING` / retry 3 / 둘 다 만료 | 둘 다 `FAILED` |
| `embedding CANCELLED` / `keyword CANCELLED` / retry 3 | 변경 없음 |

FAILED는 자동으로 되살아나지 않으며, 되살리는 경로도 없습니다. Context는 불변이므로 같은 `context_id`를 다시 처리하는 경로가 존재하지 않습니다. 사용자가 본문을 수정하면 구 Context는 CANCELLED가 되고, 새 `context_id`가 새 PENDING State로 시작합니다.

## 7. 부분 재개와의 관계

Scheduler는 단계별 상태를 구분해서 다룹니다. 두 단계를 한 덩어리로 취급하지 않습니다.

```text
embedding_status = COMPLETED
keyword_status   = PENDING (만료)
→ 재스캔 대상
→ FastAPI는 기존 Embedding의 embedding_profile이 일치하면 재사용
→ Keyword 단계부터 재개, Embedding API 재호출 없음
```

재사용 판단은 FastAPI가 합니다. Spring은 "이 Context를 다시 처리해 달라"고만 요청하고 어느 단계부터 시작할지 지시하지 않습니다. 요청 자체가 상태 기반 멱등이므로 단계 지정이 불필요합니다.

부분 재사용은 **같은 `context_id` 안에서만** 성립합니다. 수정으로 만들어진 신 Context는 다른 `context_id`이므로 구 Context의 Embedding을 재사용하지 않으며, Embedding 단계부터 처음 수행합니다.

## 8. 관찰 지점

- 회차별 재스캔 후보 건수
- 회차별 Finalizer 종결 건수
- `retry_count` 분포
- 상태별 잔량 (PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED)
- `retry_count >= 3`이면서 PENDING/PROCESSING인 잔량

후보 건수가 지속적으로 배치 크기에 붙어 있으면 FastAPI가 처리량을 못 따라가고 있다는 신호입니다.

마지막 항목은 Finalizer의 건강 상태를 나타냅니다. 정상이라면 한 주기 안에 0으로 수렴해야 하며, 값이 계속 쌓이면 Finalizer가 동작하지 않고 있다는 뜻입니다.
