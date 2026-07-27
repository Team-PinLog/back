# BD-16. 비동기 AI 처리를 메시지 큐 없이 DB State + Scheduler로

- **상태**: Accepted
- **날짜**: 2026-07-22 (AI 설계 확립 시점. 단일 커밋으로 특정 불가)
- **작성 시점**: 2026-07-27 — 결정 이후에 정리
- **관련**: S15P11A705-76
- **공용 계약**: [05_AI_설계 §5.1](https://github.com/Team-PinLog/docs/blob/main/static/05_AI_설계.md) · [10_MVP_기능범위 §2](https://github.com/Team-PinLog/docs/blob/main/static/10_MVP_기능범위.md)

## 맥락

Context 저장 후 임베딩·Keyword 생성은 FastAPI가 비동기로 처리한다. Core 저장 트랜잭션 바깥에서 일어나므로 요청이 유실될 수 있다.

- FastAPI 호출이 실패해도 Core 저장은 성공 상태를 유지해야 한다.
- AI 실패로 Context 저장을 롤백하지 않는다.
- 그러면 유실된 요청을 **무엇을 근거로** 다시 처리할 것인가.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) Kafka·RabbitMQ 도입 | 재시도·순서·확장이 검증된 방식. 다른 파트도 구독 가능 | 인프라 구성요소가 하나 더 늘고 운영·모니터링이 따라온다. MVP 규모에 과하다 |
| (b) Outbox 패턴 + 릴레이 | 트랜잭션과 발행의 원자성이 보장됨 | outbox 테이블·릴레이 프로세스·중복 발행 처리가 모두 필요. 결국 (c)와 비슷한 것을 더 복잡하게 만든다 |
| **(c) AI State를 DB에 영속하고 Spring Scheduler가 재스캔** | 새 인프라가 없다. 상태와 재처리 근거가 한 곳에 있다 | 폴링 비용. 처리량 상한이 스캔 주기에 묶인다 |

## 결정

**(c)를 채택한다. 능동적 선택이자 MVP 범위 제약이다.** [10_MVP_기능범위](https://github.com/Team-PinLog/docs/blob/main/static/10_MVP_기능범위.md)가 메시지 큐와 별도 Outbox를 명시적으로 제외했다.

결정적인 것은 **AI State가 이미 DB에 영속된다**는 점이다. `embedding_status`·`keyword_status`·`retry_count`가 행으로 남으므로, 재처리 대상은 큐를 뒤지지 않고 상태 컬럼을 조회하면 나온다. 큐를 추가해도 진실의 원본은 여전히 DB State여야 하니, 큐는 중복 장치가 된다.

```text
Context INSERT → AI State PENDING → Core Commit → FastAPI 처리 요청
                                                   (실패해도 Core는 성공)
Spring Scheduler → PENDING·시간 초과 PROCESSING을 재스캔 → 재요청
```

Spring이 retry 소진 작업을 `FAILED`로 종결하는 책임도 갖는다(P10). 상태 쓰기 책임 분담은 P13에 있다.

## 결과

**감수하는 것**

- **폴링 비용** — Scheduler가 주기적으로 상태 테이블을 스캔한다. 대상이 없어도 쿼리는 돈다.
- **지연이 스캔 주기에 묶인다** — 요청이 유실되면 다음 스캔까지 처리되지 않는다. Keyword가 비어 있는 상태를 화면이 허용해야 하는 이유이기도 하다.
- **재스캔 로직이 단순하지 않다** — `processing_timeout` 초과 판정, `retry_count` 관리, 소진 시 `FAILED` 종결이 애플리케이션 코드로 들어온다. 큐가 제공했을 재시도 기능을 직접 구현하는 셈이다.
- **다른 파트가 이벤트를 구독할 수 없다** — 발행-구독 지점이 없다. 필요해지면 DB를 폴링하거나 큐를 도입해야 한다.
- **처리량 상한** — 스캔 주기와 배치 크기가 곧 상한이다.

순서 보장이 없는 것은 문제가 되지 않는다. 처리 단위가 Context이고 Context는 불변이라([BD-06](BD-06-context-immutability.md)) 서로 다른 작업이 같은 행을 덮어쓰지 않는다.

**재검토 트리거**

- 처리량이 폴링으로 감당되지 않거나 지연이 사용자 체감 문제가 되면 → 큐 도입을 재검토한다. 이때도 DB State는 진실의 원본으로 남긴다.
- 다른 파트가 Context 생성·삭제 이벤트를 구독해야 하면 → 그때가 발행-구독이 실제로 필요해지는 시점이다.
