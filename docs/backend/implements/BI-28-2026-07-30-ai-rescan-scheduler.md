# BI-28. 재스캔 Scheduler와 FAILED Finalizer

- **상태**: ✅ 완료
- **날짜**: 2026-07-30
- **관련**: Jira 작업,
  [BD-40](../decisions/BD-40-scheduling-with-dedicated-scheduler-and-no-distributed-lock.md),
  [BI-22](BI-22-2026-07-29-context-ai-enqueue.md)(접수 방향),
  [BI-23](BI-23-2026-07-29-ai-derived-invalidation-on-delete.md)(무효화 방향),
  AI 파트 소유 명세 `docs/ai/spec/ai-rescan-scheduler.md`,
  공용 계약 `Team-PinLog/docs` `static/05_AI_설계.md` §10.3·§10.4

정책의 정본은 AI 파트가 소유한 `docs/ai/spec/ai-rescan-scheduler.md`다. 이 문서는 **Spring에서 어떻게
구현했고 무엇을 검증했는가**만 다룬다.

## 왜 필요했나

AI 연동의 실패 경로 **네 곳이 모두 "재스캔이 복구한다"를 안전망으로 전제**하는데 그 재스캔이 없었다.

| 실패 지점 | 코드가 하는 말 |
|---|---|
| `AiIntegrationConfig` 큐 포화 | *"버려진 요청은 PENDING으로 남아 재스캔 대상이 되므로 유실이 아니다"* |
| `AiProcessClient` (401 포함 전부 삼킴) | *"PENDING이 남아 있으므로 재스캔이 같은 Context를 다시 집는다"* |
| `ContextAiRequestedListener` 조립 실패 | *"PENDING은 이미 커밋되어 있어 재스캔이 같은 Context를 다시 집는다"* |
| FastAPI가 `202` 이후 내부에서 실패 | 통보 경로가 없다 |

증상은 **한 번 실패한 Context가 영구히 `PENDING`으로 남는 것**이다. 상태만 보면 "처리 대기 중"이라
정상과 구별되지 않는다.

## 산출

### 한 회차

```text
AiRescanScheduler#runOnce   @Scheduled(fixedDelayString = "${pinlog.ai.rescan.interval}")
 1. AiFailedFinalizer#finalizeExpired      @Transactional   ← retry_count >= 3 만료 → FAILED
 2. AiRescanCandidateService#claimStale    @Transactional   ← retry_count < 3 만료 잠금 + retry 증가
    ────────── 커밋 ──────────
 3. ContextProcessRequestAssembler#assemble(contextId)      ← Core 재조회 = 삭제 확인
 4. AiProcessClient#process                                 ← 트랜잭션 밖
```

**Bean이 셋으로 갈린 것은 트랜잭션 프록시 때문이다.** 한 클래스에 두면 자기 메서드 호출이 프록시를
지나지 않아 트랜잭션 없이 돌고, 그러면 `FOR UPDATE SKIP LOCKED`의 잠금이 조회 직후 풀려 **중복 방어가
조용히 사라진다.** 스케줄러 자신은 트랜잭션을 열지 않는다 — 열면 뒤의 HTTP 호출 시간만큼 행 잠금과 DB
커넥션이 붙잡힌다(명세 3.1의 커밋 경계).

### `AiRescanStateRepository` — 만료 술어를 하나로 공유한다

재스캔(4.1)과 Finalizer(6.1)가 **같은 만료 술어**를 쓴다. 명세 6.1이 "만료 기준은 재스캔과 동일하다"고
정하므로 두 SQL에 따로 적으면 한쪽만 고쳐 기준이 갈라진다. `String.formatted`로 한 상수를 두 쿼리에
끼운다.

```sql
WHERE retry_count < :maxRetry          -- Finalizer는 >=
  AND (
      (embedding_status = 'PENDING' AND updated_at < now() - make_interval(secs => :pendingSecs))
   OR (keyword_status = 'PENDING' AND updated_at < now() - make_interval(secs => :pendingSecs))
   OR (embedding_status = 'PROCESSING' AND updated_at < now() - make_interval(secs => :procSecs))
   OR (keyword_status = 'PROCESSING' AND updated_at < now() - make_interval(secs => :procSecs))
  )
ORDER BY updated_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

- **시각 비교를 DB `now()`로 한다.** 자바에서 컷오프를 계산해 넘기면 인스턴스마다 시계가 달라 만료
  시점이 갈라진다. 만료값은 `make_interval(secs => ...)`로 넘기고 파라미터를 `double`로 보낸다 — 그
  함수의 인자 타입이 `double precision`이라 값도 그 타입이면 함수 해석에 추론이 끼어들지 않는다.
- **`FAILED`·`CANCELLED`를 조건에 적지 않는다.** 만료 술어가 `PENDING`·`PROCESSING` 화이트리스트라
  자동으로 빠진다. 블랙리스트(`<> 'COMPLETED'`)로 쓰면 나중에 추가되는 status가 조용히 후보가 된다.
- `retry_count` 증가는 status를 손대지 않는다. **만료된 `PROCESSING`을 `PENDING`으로 되돌리지
  않는다** — Spring은 `PROCESSING`을 쓰지도 해제하지도 않고, FastAPI의 선점 UPDATE가 `PROCESSING`을
  허용 조건에 포함하므로 재요청만으로 재개된다(명세 5장). 되돌리면 두 주체가 같은 컬럼을 경쟁적으로
  쓰게 되어 소유권 경계가 무너진다.
- 종결 UPDATE의 `CASE`는 **화이트리스트**다. 후보를 잡은 뒤 UPDATE 직전에 삭제·교체가 끼어들 수 있고,
  블랙리스트면 그 창에서 `CANCELLED`가 `FAILED`로 뒤집혀 "사용자가 지웠다"와 "AI가 실패했다"를 구별할
  수 없게 된다(명세 6.3).

### `ContextProcessRequestAssembler` — 진입점을 하나 더 두었다

재스캔은 상태 행에서 후보를 집으므로 `context_id`뿐이다. `assemble(long contextId)`를 더해 `member_id`·
`record_id`를 Context의 비정규화 컬럼에서 읽는다. **기존 이벤트 경로(`assemble(ContextAiRequested)`)의
동작은 바꾸지 않았다** — 그쪽은 발행 시점의 값을 그대로 쓰고, 이미 검증된 경로다. 공통 본문만 private
메서드로 뺐다.

`Context`에 `@SQLRestriction("deleted_at IS NULL")`이 걸려 있어 **이 재조회가 곧 삭제 확인이다**(명세
5.1). 삭제된 것과 수정으로 교체된 구버전이 같은 경로로 함께 빠지므로 둘을 구분하지 않는다.

### `warnUnlessCancelled` — 명세 5.1의 정합성 경고

Context가 사라졌으면 그 상태는 `CANCELLED`여야 한다. 아니라면 삭제·수정 트랜잭션이 무효화를 빠뜨렸다는
뜻이므로 경고를 남긴다. **후보 조회 결과로는 판정할 수 없다** — 후보 조회는 `CANCELLED`를 애초에 잡지
않으므로(화이트리스트), 삭제가 그 뒤에 일어났을 가능성을 보려면 그 시점의 값을 다시 읽어야 한다.
그래서 단건 조회 메서드가 하나 더 있다.

### 스케줄링 — 이 레포에 처음 들어온다

`@EnableScheduling`을 `AiIntegrationConfig`에 두었다. `@EnableAsync`와 같은 사정이다 — 둘 다 전역
스위치인데 켜야 하는 이유가 이 연동에만 있고, `global/config`로 올리면 스위치와 유일한 소비자가 떨어져
앉는다. 전용 `ThreadPoolTaskScheduler`(`ai-rescan-`, 풀 2)를 두어 Boot의 기본 단일 스레드 스케줄러를
쓰지 않는다. Bean 이름을 `taskScheduler`로 점유한 이유와 감수하는 것은 BD-40에 있다.

`domain/ai/scheduler` 패키지를 새로 만들었고 `package-structure.md`의 `ai` 행을 먼저 갱신했다.
`event`와 가른 기준은 **무엇이 호출을 촉발하는가**이고, 그에 따라 트랜잭션 경계도 다르다.

`AiRescanProperties`는 그 패키지가 아니라 `service`에 있다. 처음에 `scheduler`에 두었는데 **소비자
둘이 모두 `service`에 있어 패키지 의존이 순환했다**(`service` → `scheduler` → `service`). 설정은
소비자와 같은 패키지에 둔다는 규약(`package-structure.md`)을 따르면 순환도 함께 사라진다 — 스케줄러가
읽는 것은 주기 하나이고, 그것은 애노테이션 문자열이라 타입 참조가 아니다.

### 설정

```yaml
pinlog:
  ai:
    rescan:
      interval: PT5M            # ← ISO-8601. 아래 함정 참조
      pending-expiry: 5m
      processing-expiry: 10m
      max-retry: 3
      batch-size: 100
```

`interval`만 ISO-8601이다. 이 값은 Boot의 완화된 바인딩이 아니라 **`@Scheduled(fixedDelayString)`이
직접 파싱**하고, 그쪽은 숫자(밀리초)나 ISO-8601만 받는다. 다른 키들처럼 `5m`으로 적으면
`NumberFormatException`으로 **기동이 실패한다.** `AiRescanProperties`에는 같은 값이 `Duration`으로도
들어 있다 — 애노테이션의 문자열이 무슨 값인지 타입으로 드러나지 않기 때문이다.

`max-retry`의 정본은 **DB의 `CHECK (retry_count BETWEEN 0 AND 3)`**(`V100__ai_tables.sql`)이다. 이
값만 올리면 증가 UPDATE가 제약 위반으로 실패한다. Backoff는 두지 않는다(명세 2장).

## 검증

`AiRescanSchedulerTests` — PostgreSQL Testcontainers(`pgvector/pgvector:0.8.5-pg16`), 테스트 13개.
`AiRescanSchedulerOrderTest` — 대역·리플렉션, 2개. `ConfigurationContractTests`에 파라미터 계약 1개.

### 만료를 만드는 방법과 임계값을 덮는 이유

5분을 기다릴 수 없으므로 `updated_at`을 과거로 밀어 넣는다. 임계값을 기본값(5분·10분)이 아닌
**2분·4분으로 덮고** 세 행의 나이를 그 사이에 배치해 한 회차에서 갈라지는 것을 본다 — 임계값이 코드
상수라면 이 배치가 성립하지 않으므로, 이 테스트가 곧 "설정으로 주입된다"의 확인이다.

기본값 자체는 어느 통합 테스트도 지키지 못한다(각자 덮으므로). 그래서
`ConfigurationContractTests`가 `application.yml`의 다섯 값을 파일로 고정한다.

### 회차마다 모든 상태 행의 나이를 0으로 돌린다

컨테이너는 JVM이 공유하고 이 클래스는 실제로 커밋하므로, 앞선 테스트가 남긴 만료 행이 다음 회차에
후보로 섞인다. `@BeforeEach`·`@AfterEach`에서 `UPDATE ai.context_ai_state SET updated_at = now()`를
돌려 앞뒤로 격리했다 — 뒤도 정리하는 이유는 다른 테스트 클래스의 배경 회차에 만료 행을 물려주지 않기
위해서다.

Core에 없는 `context_id`는 **음수**로 만든다. `ai.context_ai_state`에는 `core.context`로 향하는 FK가
없어(V100) 그런 행을 만들 수 있고, 음수면 IDENTITY가 만드는 실제 id와 절대 겹치지 않는다. 상태 전이만
보는 테스트는 Record·Place 조립이 필요 없다.

### `SKIP LOCKED`를 실제로 관측한다

리더 선출을 두지 않는 근거가 이 동작이므로 주장으로 남기지 않았다. 다른 커넥션(`DriverManager`)이 한
행을 `FOR UPDATE`로 붙잡은 채 회차를 돌리고, 그 행의 `retry_count`가 그대로이며 다른 만료 행은 처리된
것을 확인한다.

**회차를 별 스레드에서 돌린다.** `SKIP LOCKED`가 빠지면 후보 조회가 잠금을 기다리며 멈추는데, 같은
스레드에서 부르면 테스트가 실패하는 대신 영원히 매달린다. 15초 타임아웃을 걸어 **실패로** 드러나게
했다.

### "Finalize를 먼저" — 명세의 근거가 실측에서 관측되지 않았다

명세 3.1은 Finalize를 먼저 두는 이유를 *"나중에 두면 같은 회차에서 방금 `retry_count`를 3으로 올린 행을
곧바로 FAILED로 종결한다"*로 든다. 그것을 결과로 고정하려고 `retry_count = 2` 만료 행으로 테스트를
썼는데, **`runOnce`의 두 줄을 맞바꿔도 통과했다.**

원인은 재시도 증가가 `updated_at`을 함께 갱신하는 것이다. 증가 직후 그 행은 **만료 상태에서 벗어나**
Finalizer 후보 조건(6.1의 만료 조건)에 걸리지 않는다. 즉 마지막 재시도의 창을 실제로 확보하는 것은
순서가 아니라 **Finalizer의 만료 조건 + 증가 시 `updated_at` 갱신**이다. 명세 6.1도 그 둘이 "함께 그
창을 확보한다"고 쓰고 있으니 명세와 어긋나는 관측은 아니지만, **순서만으로 그 창이 생긴다는 읽기는
사실이 아니다.**

그래서 검증을 셋으로 나눴다.

| 고정하는 것 | 어디서 |
|---|---|
| 3회차 요청이 실제로 나가고 그 회차에서 종결되지 않는다 | `theLastRetryActuallyGoesOutAndIsNotFinalizedInTheSameRound` |
| Finalizer에 만료 조건이 붙어 있다(창을 만드는 실제 장치) | `anExhaustedRowThatIsNotExpiredYetIsLeftAlone` |
| 호출 순서 자체(심층 방어) | `AiRescanSchedulerOrderTest` — Mockito `InOrder` |

순서를 심층 방어로 남기는 이유: 누군가 증가 UPDATE에서 `updated_at` 갱신을 빼면 순서가 유일한 보호가
된다.

### RED 확인

구현을 하나씩 되돌려 테스트가 실제로 잡는지 확인했다.

| 되돌린 것 | 실패한 테스트 |
|---|---|
| `runOnce`의 Finalize를 재스캔 뒤로 | `finalizeRunsBeforeTheCandidateClaimInEveryRound` (**통합 테스트는 통과 — 위 절 참조**) |
| Finalizer 후보 조회에서 만료 조건 제거 | `anExhaustedRowThatIsNotExpiredYetIsLeftAlone` |
| `FOR UPDATE SKIP LOCKED` → `FOR UPDATE` | `skipLockedLeavesALockedRowToWhoeverHoldsIt` (15초 타임아웃) |
| 종결 `CASE`를 화이트리스트 → 블랙리스트(`<> 'COMPLETED'`) | `theFinalizerKeepsCompletedStagesAndNeverOverwritesCancelled` (`CANCELLED`가 `FAILED`로 뒤집힌다) |
| `PROCESSING` 만료값을 `PENDING`과 같게 | `expiryThresholdsComeFromConfigurationAndDifferByStage` |
| `fixedDelayString` → `fixedRateString` | `theRoundIsScheduledWithFixedDelayAndReadsTheConfiguredInterval` |

### 실행 결과

```text
./gradlew clean check --no-daemon   →  BUILD SUCCESSFUL
386 tests, 0 failures, 0 skipped
jacoco  LINE 96.45%  BRANCH 82.59%   (게이트 80%)
```

## 남은 것

- **관측·알림 파이프라인**(명세 8장). 회차별 후보·종결 건수는 `INFO` 로그 한 줄로만 남는다. 지표
  (`retry_count` 분포, 상태별 잔량, `retry_count >= 3`이면서 `PENDING`/`PROCESSING`인 잔량)는 없다.
  마지막 항목이 Finalizer의 건강 상태이며 정상이면 한 주기 안에 0으로 수렴해야 한다.
- **`FAILED` 전환 로그에 "마지막 실패 사유"가 없다.** 명세 6.3이 요구하지만 **그 값이 Spring 쪽에
  존재하지 않는다** — 202 이후의 실패는 FastAPI 내부에서 일어나고 통보 경로가 없다. 가진 단서(종결 직전
  단계별 status·소진한 재시도 횟수)를 남기고 사유를 어디서 찾아야 하는지 문장으로 가리켰다. 사유를
  실을 수 있으려면 FastAPI가 실패를 어딘가 기록해야 하고, 그것은 `Jira 작업`(`ai#44`) 소관이다.
- **회차 안 HTTP 호출은 순차다.** 배치 100건이 전부 재요청 대상이면 한 회차가 길어진다. `fixedDelay`라
  겹치지는 않지만 만료 행이 밀린다. 그 상황이 실제로 보이면 BD-40의 재검토 트리거를 따른다.
- **스레드 이름 `ai-rescan-`은 두 번째 배치가 붙는 순간 거짓말이 된다.** BD-40의 감수 목록에 있다.
