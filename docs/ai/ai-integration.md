# FastAPI Client와 호출 시점

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Spring이 FastAPI AI Server를 호출하는 방법을 정의합니다. 내부 API의 논리 계약(`POST /internal/v1/context/process`, `POST /internal/v1/search`, 요청·응답 필드)은 공용 계약이 원본이며 여기서 재정의하지 않습니다.

Spring은 Embedding API와 LLM API를 직접 호출하지 않습니다. 외부 모델 호출은 전부 FastAPI가 담당합니다.

## 2. Client 구성

| 항목 | 값 |
|---|---|
| HTTP Client | Spring Framework `RestClient` (동기) |
| Base URL | 설정 주입 (`pinlog.ai.base-url`) |
| 위치 | `com.pinlog.pinlogback.ai.client` |

`RestClient`를 쓰는 이유는 두 가지입니다.

- 호출이 `202 Accepted`만 확인하면 끝나는 짧은 요청이라 reactive stack을 도입할 이유가 없습니다.
- 프로젝트에 `spring-boot-starter-webmvc`만 있으므로 WebFlux 의존성을 추가하지 않습니다.

`RestClient`는 `RestClient.Builder`로 Bean 하나를 만들고, 그 위에 도메인 인터페이스를 얹습니다.

```text
AiProcessClient   -- Context 처리 요청
AiSearchClient    -- 개인 자연어 검색 요청
```

두 Client는 타임아웃과 실패 정책이 다르므로 별도 `RestClient` 인스턴스를 갖습니다.

### 2.1 설정 키

```yaml
pinlog:
  ai:
    base-url: http://localhost:8000
    internal-token: ${PINLOG_AI_INTERNAL_TOKEN}
    embedding-profile: openai-text-embedding-3-small-1536-cosine-v1
    process:
      connect-timeout: 1s
      read-timeout: 3s
    search:
      connect-timeout: 1s
      read-timeout: 5s
```

`embedding-profile`은 Spring과 FastAPI 양쪽에 하드코딩하지 않고 배포 환경의 단일 설정에서 주입합니다. Spring은 이 값을 검색 요청에 실어 보내는 역할만 하며 해석하지 않습니다.

`@ConfigurationProperties`로 바인딩하고 값 자체는 코드에 상수로 두지 않습니다.

## 3. 타임아웃

| 호출 | Connect | Read | 근거 |
|---|---|---|---|
| `POST /internal/v1/context/process` | 1s | 3s | 접수 확인만 받으므로 짧게. 초과 시 재스캔이 복구 |
| `POST /internal/v1/search` | 1s | 5s | 질의 Embedding 생성과 벡터 검색이 응답 경로에 포함됨 |

`process`는 재시도하지 않습니다. 실패는 Scheduler가 흡수합니다. 애플리케이션 레벨 즉시 재시도를 넣으면 FastAPI 장애 시 스레드를 점유해 Core 요청 처리량까지 떨어집니다.

`search`는 사용자 요청 경로이므로 타임아웃 시 재시도 없이 즉시 실패 응답으로 변환합니다.

## 4. 호출 시점 — Core 커밋 이후

### 4.1 원칙

FastAPI 호출은 **Core 트랜잭션 안에서 수행하지 않습니다.**

트랜잭션 안에서 호출하면 다음 문제가 생깁니다.

- 외부 호출 지연만큼 DB 커넥션과 행 잠금이 유지됩니다.
- FastAPI가 아직 커밋되지 않은 `context_ai_state`를 조회하려 시도할 수 있습니다.
- AI 호출 실패가 롤백을 유발해 Record 저장을 취소시킵니다. 이는 공용 원칙 5·6 위반입니다.

### 4.2 구현 방식

Core 서비스는 도메인 이벤트만 발행하고, 이벤트 리스너가 커밋 이후에 호출합니다.

```text
ContextService (@Transactional)
  → Core 저장 + context_ai_state PENDING
  → ApplicationEventPublisher.publishEvent(ContextAiRequested)
  → 커밋

ContextAiRequestedListener
  @TransactionalEventListener(phase = AFTER_COMMIT)
  @Async("aiCallExecutor")
  → AiProcessClient.process(...)
```

- `AFTER_COMMIT`이므로 롤백된 트랜잭션에서는 호출이 발생하지 않습니다.
- `@Async`를 붙여 응답 지연이 사용자 요청 시간에 포함되지 않게 합니다.
- 전용 `ThreadPoolTaskExecutor`(`aiCallExecutor`)를 사용하고 큐가 가득 차면 `CallerRunsPolicy` 대신 **버립니다**. 버려진 요청은 PENDING 상태로 남아 재스캔 대상이 되므로 유실이 아닙니다.

이벤트 payload에는 엔티티를 담지 않고 `contextId`, `memberId`, `recordId`만 담습니다. 리스너는 별도 읽기 전용 트랜잭션에서 해당 `context_id`의 Context 본문과 Place metadata를 다시 조회해 요청을 만듭니다. 엔티티를 그대로 실어 보내면 영속성 컨텍스트 밖에서 지연 로딩이 터지고, 커밋 시점 스냅샷을 담아두면 재사용 경로에서 무엇이 최신인지 판단할 근거가 사라지기 때문입니다.

Context는 불변이므로 재조회한 본문은 이벤트 발행 시점의 본문과 항상 같습니다. 그 사이에 사용자가 수정했다면 이 `context_id`는 이미 삭제·CANCELLED 상태이고, 수정 요청이 만든 **다른 `context_id`**에 대해 별도 이벤트가 발행되어 있습니다. 따라서 조회 결과가 삭제 상태이면 호출을 생략하면 되고, 잘못된 본문을 보낼 위험은 없습니다.

### 4.3 Context 수정 시 호출

수정은 구 Context 삭제와 신 Context 생성의 조합이며, 두 동작이 한 Core 트랜잭션 안에서 처리됩니다. 호출 관점에서 지켜야 할 것은 두 가지입니다.

- 커밋 이후 **신 `context_id`로만** `process`를 호출합니다. 구 `context_id`로는 호출하지 않습니다.
- 구 Context에 대한 취소 통보 API를 두지 않습니다. 구 State가 CANCELLED이므로 FastAPI가 스스로 폐기합니다.

트랜잭션이 롤백되면 `AFTER_COMMIT` 리스너가 실행되지 않으므로 신 Context에 대한 호출도 발생하지 않습니다. 트랜잭션 의미론의 원본은 [`context-state-sync.md`](context-state-sync.md) 6장입니다.

### 4.4 `process` 요청 payload

논리 계약은 공용 계약 `static/05_AI_설계.md` §13.1이 원본입니다. Spring이 실어 보내는 값은 다음이 전부입니다.

```text
contextId
userId
recordId
text
placeMeta
```

Context 본문 버전 필드는 보내지 않습니다. `context_id`가 곧 본문의 정체성이므로 FastAPI가 버전을 비교할 이유가 없습니다.

**같은 `context_id`로 다른 `text`를 보내는 것은 계약 위반입니다.** FastAPI는 이를 정상적인 Context 수정으로 처리하지 않으며, 상태 검사만 통과하면 낡은 본문이 그대로 저장될 수 있습니다. 따라서 Spring 구현에서 다음을 보장해야 합니다.

- 요청의 `text`는 반드시 그 `context_id`의 Core 본문을 그대로 조회해서 채웁니다. 다른 곳에서 넘어온 문자열을 그대로 신뢰하지 않습니다.
- 수정은 언제나 새 `context_id`로 호출합니다. 기존 `context_id`에 새 본문을 실어 재호출하는 코드 경로를 만들지 않습니다.
- 재시도 요청도 동일합니다. 이전 요청 본문을 보관했다가 재전송하지 않고 매번 재조회합니다.

## 5. Fire-and-Forget 의미

`process` 호출은 fire-and-forget입니다. 정확히 다음을 의미합니다.

- 응답 `202 Accepted`는 **접수**만 뜻하며 처리 완료가 아닙니다.
- 완료 통보용 웹훅이나 콜백을 두지 않습니다. FastAPI가 Spring을 역호출하지 않습니다.
- Spring은 완료 여부를 알아야 할 때 `ai.context_ai_state`를 **직접 조회**합니다.
- 호출 결과를 Core 트랜잭션 결과에 반영하지 않습니다.

즉 Spring 입장에서 이 호출은 "지금 처리하면 조금 빨라지는 힌트"이고, 정합성의 근거는 DB에 영속된 AI State입니다.

## 6. 호출 실패 시 동작

| 실패 유형 | Spring 동작 |
|---|---|
| Connection refused / DNS 실패 | WARN 로그, 예외 삼킴 |
| Timeout | WARN 로그, 예외 삼킴 |
| `4xx` | ERROR 로그(요청 payload 형식 문제 가능성), 예외 삼킴 |
| `5xx` | WARN 로그, 예외 삼킴 |
| Executor 큐 포화로 미실행 | DEBUG 로그, 무시 |

모든 경우에 공통입니다.

- Core 데이터는 그대로 유지됩니다.
- `context_ai_state`는 PENDING으로 남습니다.
- `retry_count`를 여기서 증가시키지 않습니다. 증가 주체는 Scheduler입니다.
- **상태를 FAILED로 바꾸지 않습니다.** 호출 실패는 FastAPI 내부 작업의 실패가 아니므로 Spring이 이를 대신 판단해 기록하지 않습니다. Spring이 쓰는 유일한 FAILED는 재시도 소진 Finalizer뿐입니다. 상세는 [`context-state-sync.md`](context-state-sync.md) 8장을 참조합니다.
- 사용자 응답은 성공입니다. AI 호출 실패를 API 오류로 노출하지 않습니다.

PENDING이 만료(5분)되면 재스캔이 같은 Context를 다시 집어 처리합니다. 상세는 [`ai-rescan-scheduler.md`](ai-rescan-scheduler.md)를 참조합니다.

검색(`POST /internal/v1/search`) 실패는 성격이 다릅니다. 사용자에게 결과를 줄 수 없으므로 삼키지 않고 오류 응답으로 변환합니다. 다만 Place 이름 검색·지도 검색은 별개 경로이므로 영향받지 않습니다.

## 7. 내부 인증

FastAPI는 User 인증을 판단하지 않습니다. Spring은 **서비스 간 인증**만 실어 보냅니다.

- 내부 Network에서만 도달 가능하도록 배포합니다. FastAPI를 외부에 노출하지 않습니다.
- 모든 내부 호출에 공유 시크릿 헤더를 붙입니다.

```text
X-Internal-Token: <pinlog.ai.internal-token>
```

- 토큰 값은 환경 변수로 주입하며 저장소에 커밋하지 않습니다.
- 요청 상관관계 추적을 위해 `X-Request-Id`를 함께 전달합니다. Spring이 생성하고 로그 양쪽에 남깁니다.
- `userId`는 인증값이 아니라 **검색 범위 필터값**으로 전달합니다. FastAPI는 이 값을 신뢰하며 검증하지 않으므로, 요청 User와 `userId`의 일치는 Spring이 호출 전에 보장해야 합니다.
- Client는 FastAPI를 직접 호출하지 않습니다. Spring이 FastAPI URL을 응답에 노출하지 않습니다.

## 8. 관찰 지점

운영 중 문제 판별에 필요한 최소 지표입니다.

- `process` 호출 성공/실패 건수와 실패 사유 분포
- `search` 호출 지연 분포
- 상태별 `context_ai_state` 건수 (PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED)
- PENDING이 5분 이상 유지된 건수

마지막 두 개는 FastAPI 장애를 가장 먼저 드러내는 신호입니다.
