# BI-22. Context 생성·교체 시 AI 처리 접수

- **상태**: ✅ 완료
- **날짜**: 2026-07-29
- **관련**: Jira 작업, [back#61](https://github.com/Team-PinLog/back/issues/61),
  [BD-36](../decisions/BD-36-pending-insert-in-transaction-process-call-after-commit.md),
  [BI-21](BI-21-2026-07-29-ai-derived-invalidation-on-delete.md)(back#80, 삭제 방향),
  AI 파트 소유 명세 `docs/ai/spec/context-state-sync.md`·`docs/ai/spec/ai-integration.md`,
  공용 계약 `Team-PinLog/docs` `static/05_AI_설계.md` §6.4·§13.1

정책의 정본은 `Team-PinLog/docs`의 `static/` 문서이고, 연동 명세의 정본은 AI 파트가 소유한
`docs/ai/spec/`이다. 이 문서는 **Spring에서 어떻게 구현했고 무엇을 검증했는가**만 다룬다.

## 산출

### 흐름

```text
RecordService (@Transactional)
  core.context INSERT
  → ai.context_ai_state PENDING INSERT      ← ContextAiStateRepository
  → ContextAiRequested 발행
커밋
→ ContextAiRequestedListener  @TransactionalEventListener(AFTER_COMMIT) @Async("aiCallExecutor")
     → ContextProcessRequestAssembler  (별 읽기 트랜잭션에서 본문·Place 재조회)
     → AiProcessClient  POST /internal/v1/context/process
```

**서비스는 호출하지 않는다.** 호출 코드가 `AFTER_COMMIT` 리스너 한 곳에만 있으므로 "PENDING이
커밋된 뒤에 호출한다"가 관습이 아니라 트랜잭션 경계로 강제된다. 근거는 BD-36.

### Context INSERT 지점 — 실제 3곳

티켓 본문은 4곳이라고 적었으나 코드에는 3곳이다. 셋 다 `RecordService`에 있고 모두
`enqueueAiProcessing(Context)` 한 줄을 공유한다.

| 계약 | 코드 | 성격 |
|---|---|---|
| 데이터모델 6.1·6.3 Record 생성(첫 Context 포함) | `RecordService#create` | 신규 |
| 데이터모델 6.3 Context 추가 | `RecordService#addContext` | 신규 |
| 데이터모델 6.4 Context 교체 수정 | `RecordService#replaceContext` | 신 Context만 |

`create`는 Place 중복 시 Record 생성 대신 Context 추가로 흡수하는데(BD-12), 두 갈래 모두 같은
`contextRepository.save()` 한 줄을 지나므로 접수도 한 곳이면 된다. 티켓이 센 4곳은 이 두 갈래를
따로 센 것으로 보인다.

`replaceContext`에서는 **신 Context만** 접수한다. 구 Context의 `CANCELLED` 전이는 삭제 경로
(back#80 · BI-21)의 몫이다. 구 상태를 신 Context로 승계하지 않으므로 `retry_count`도 0에서 새로
시작한다(`context-state-sync.md` §5.2).

`create`의 `contextRepository.save()` 반환값을 그동안 버리고 있었다. id가 필요해져 지역 변수로
받았다. `Context`는 `GenerationType.IDENTITY`라 `save()` 시점에 INSERT가 나가고 id가 채워진다.

### `ContextAiStateRepository` — 백엔드가 `ai`에 새로 쓰는 전부

```sql
INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status, retry_count, updated_at)
VALUES (:contextId, 'PENDING', 'PENDING', 0, now())
ON CONFLICT (context_id) DO NOTHING
```

- **`DO NOTHING`이지 `DO UPDATE`가 아니다.** 상태를 되돌리는 전이는 존재하지 않으므로
  (`context-state-sync.md` §5.3) 기존 행을 덮으면 `COMPLETED`·`CANCELLED`를 `PENDING`으로
  되살리는 통로가 열린다.
- 기본값을 DB DEFAULT에 맡기지 않고 명시했다. 재시도 예산이 `context_id` 단위라는 사실이 SQL에
  드러나야 한다.
- `AiDerivedDataRepository`(back#80)와 파일을 나눈 이유는 BD-36에 적었다. 병합 순서와 트랜잭션
  경계 차이 때문이며, back#80의 기존 메서드는 손대지 않았다.

### `ContextProcessRequestAssembler` — 본문을 다시 읽는다

이벤트에는 `contextId`·`memberId`·`recordId`만 싣고 본문은 리스너가 별 읽기 트랜잭션에서 다시
읽는다. `ai-integration.md` §4.4가 "같은 `context_id`로 다른 `text`를 보내는 것은 계약 위반"이라고
못 박는데, 다른 곳에서 넘어온 문자열을 그대로 실으면 그 계약을 코드로 보장할 방법이 없다.

Context가 이미 지워졌으면 `Optional.empty()`로 **호출을 생략한다.** 예외가 아닌 이유: 커밋과 이
조회 사이에 수정·삭제가 끼어드는 것은 정상 경로이고, 그때 신 `context_id`에 대한 별도 이벤트가
이미 발행돼 있다.

### `AiProcessClient` — 실패를 전부 삼킨다

| 실패 | 로그 | 상태 |
|---|---|---|
| 연결 거부·타임아웃 | WARN | 손대지 않음 |
| `4xx` | ERROR(요청 형식 문제 가능성) | 손대지 않음 |
| `5xx` | WARN | 손대지 않음 |
| 큐 포화로 미실행 | DEBUG | 손대지 않음 |

`FAILED`로 바꾸지 않고 `retry_count`도 올리지 않는다. 백엔드가 쓰는 유일한 `FAILED`는 재시도 소진
Finalizer뿐이다(`context-state-sync.md` §8, 별건).

`X-Internal-Secret` 헤더 이름의 원본은 ai 레포 `app/core/security.py`다 — 공용 계약 §13이 "실제
Route와 Schema가 구현되면 ai 레포 코드가 실행 가능한 계약의 원본"이라고 정한다. **back의
`docs/ai/spec/ai-integration.md` §7은 `X-Internal-Token`이라고 적고 있어 어긋난다.** CLAUDE.md 9번
규칙에 따라 임의로 고치지 않고 해당 위치에 검토 표시만 남겼다.

`RestClient.Builder` Bean을 주입받지 않고 `RestClient.builder()`로 시작한다. 그 Bean은 별도
starter가 있어야 생기는데, 필요한 것은 기본 컨버터가 붙은 builder 하나뿐이라 의존성을 늘리지 않았다.

### 설정

```yaml
pinlog:
  ai:
    base-url: ${PINLOG_AI_BASE_URL:http://localhost:8000}
    internal-secret: ${PINLOG_AI_INTERNAL_SECRET:}
    process:
      connect-timeout: 1s
      read-timeout: 3s
```

검색용 키(`search`·`embedding-profile`)는 명세에 있지만 두지 않았다. 소비자가 생기는 티켓
(Jira 작업)에서 함께 들어와야 "설정은 있는데 아무도 안 읽는" 구간이 생기지 않는다.

## 검증

`ContextAiEnqueueTests` — PostgreSQL Testcontainers(`pgvector/pgvector:0.8.5-pg16`), 테스트 6개.

### 대역이 요청 핸들러 안에서 DB를 본다

이 PR에서 가장 중요한 검증 장치다. `FastApiProcessStub`은 JDK `HttpServer`이고, 요청 핸들러
안에서 **`DriverManager`로 새로 연 커넥션**으로 해당 `context_id`의 `ai.context_ai_state`를
조회한다.

- 테스트가 끝난 뒤 조회하면 "언젠가 커밋됐다"만 증명된다. 확인하려는 것은 **"호출이 도착한 그
  시점에 이미 커밋돼 있었다"**이고, 그 시점은 핸들러 안에서만 포착된다.
- 애플리케이션 커넥션 풀을 재사용하면 미커밋 변경도 보여 증명이 성립하지 않는다. 별 커넥션이라야
  별 프로세스인 AI 워커와 같은 조건이 된다.
- **이 테스트 클래스는 `@Transactional` 롤백 테스트가 아니다.** 롤백 테스트는 커밋하지 않으므로
  별 커넥션에서 애초에 아무것도 보이지 않아, 명제를 참으로도 거짓으로도 만들 수 없다. 실제로
  커밋시키고 남는 행은 테스트마다 다른 `kakaoPlaceId`로 격리했다.
- HTTP 대역에 라이브러리를 쓰지 않은 이유도 이것이다. 요청·응답만 기록해 주는 mock 서버로는
  핸들러 안에서 임의 코드를 돌릴 수 없다. 새 테스트 의존성도 필요 없었다.

### 교체는 Context가 1개인 상태에서 검증한다

"마지막 Context 삭제 금지" 가드는 마지막 1건일 때만 의미가 있다. 2개 이상 남겨 두고 검증하면
순서를 뒤집어도 아무 일이 일어나지 않아 테스트가 순서의 프록시 노릇을 못 한다. 그래서 Context가
정확히 1개인 것을 먼저 단언하고 교체한 뒤, 활성 Context가 여전히 1개이고 그것이 신 Context임을
확인한다.

> **한계**: 현재 가드는 `RecordDeletionService#deleteContext`에 있고 `replaceContext` 경로에는
> 걸려 있지 않다. 따라서 이 테스트가 고정하는 것은 "가드가 울리지 않았다"가 아니라 **"활성 수가
> 0이 되는 중간 상태가 없다"**는 관측 가능한 결과다. 두 서비스가 하나로 합쳐지거나 교체가 삭제
> 유스케이스를 재사용하게 되면 그때 가드가 실제 프록시가 된다.

### 실패 경로

`SERVER_ERROR`(503)와 `HANG_UP`(응답 없이 연결 종료) 두 모드로 Context와 PENDING 행이 남는 것을
확인한다. 전자는 `RestClientResponseException`, 후자는 연결·타임아웃 계열 분기를 탄다.

### RED 확인

구현을 일부러 되돌려 테스트가 실제로 잡는지 확인했다.

| 되돌린 것 | 실패한 테스트 |
|---|---|
| `@TransactionalEventListener(AFTER_COMMIT)` → `@EventListener`(트랜잭션 안 호출) | 3개 — `stateVisible`이 false. *"호출이 도착한 시점에 PENDING 행이 별 커넥션에서 이미 보여야 한다"* |
| 위 + 클라이언트가 예외를 되던지게 | 5개 — 실패 경로 2개가 추가로 깨진다(Context·PENDING이 롤백됨) |

### 실행 결과

```text
./gradlew clean check --no-daemon   →  BUILD SUCCESSFUL
299 tests, 0 failures, 0 skipped
```

## 남은 것

- **재스캔 Scheduler와 재시도 소진 Finalizer**(별건). 큐 포화·호출 실패로 남은 PENDING을 실제로
  복구하는 주체다. 붙기 전까지 버려진 호출은 실질적으로 유실이다.
- **검색 연동**(Jira 작업). `AiSearchClient`와 `pinlog.ai.search`·`embedding-profile` 설정.
- **관찰 지표**(`ai-integration.md` §8). 호출 성공·실패 건수와 상태별 State 건수는 아직 없다.
- **`docs/ai/spec/ai-integration.md` §7의 헤더 이름 불일치.** 위 "산출" 참조. 정본이 아니므로
  고치지 않고 표시만 남겼다.
