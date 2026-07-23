# PinLog Backend 문서

이 디렉터리는 PinLog Backend(Spring)의 **설계·구현 문서**를 관리합니다. 루트 [`README.md`](../README.md)가 기술 스택·실행·운영을 다룬다면, 여기서는 도메인 로직과 파트 간 연동을 **어떻게 구현하는지**를 다룹니다.

현재는 **AI 연동**과 **Feed 추천**을 다루며, 백엔드 기능이 늘어남에 따라 도메인·인증·API 규약 등으로 확장합니다.

## 구성

| 영역 | 위치 | 상태 |
|---|---|---|
| AI 연동 | [`ai/`](ai/) | 구현 예정 명세 |
| Feed 추천 | [`feed/`](feed/) | 구현 예정 명세 |
| 결정 기록(ADR) | [`decisions/`](decisions/) | 진행 중 |
| 트러블슈팅 | [`troubleshooting/`](troubleshooting/) | 진행 중 |
| 작업 리포트 | [`reports/`](reports/) | 진행 중 |
| 도메인·인증·API 규약 등 | (향후) | 미작성 |

### 작업 기록 (제안·트러블슈팅·리포트)

명세(`ai/`·`feed/`)가 **무엇을 구현하는가**를 다룬다면, 아래는 그것을 만들며 **왜 그렇게 정했고 무슨 문제를 어떻게 넘겼는가**를 남깁니다. 커밋·PR과 연결해 이후 복기가 가능하게 합니다.

| 구분 | 위치 | 내용 |
|---|---|---|
| 결정 | [`decisions/`](decisions/) | Flyway 번호 컨벤션, feed_event 소유, flyway.schemas 미지정 |
| 트러블슈팅 | [`troubleshooting/`](troubleshooting/) | H2·pgvector 비호환 |
| 리포트 | [`reports/`](reports/) | Flyway 도입 + ai 스키마·feed_event 마이그레이션(back#3) |

> `ai/`·`feed/` 문서는 아직 코드가 없는 **구현 예정 명세**입니다. 백엔드 코드가 생긴 뒤 실제 코드를 문서화하는 항목에는 이 표시를 달지 않습니다.

공용 계약(원칙·상태값·내부 API·Visibility·AI 데이터 구조 등)은 이 저장소에 복사하지 않습니다. `Team-PinLog/docs`의 문서가 단일 원본이며, 여기의 문서는 그 계약을 Spring에서 구현하는 방법만 기술합니다. 두 문서가 충돌하면 공용 계약이 우선입니다.

## AI 연동 — `ai/`

AI 연동 문서 전체를 관통하는 전제는 하나입니다.

```text
동일한 context_id는 항상 동일한 Context 본문을 의미한다.
```

Context는 불변 엔티티이며 본문을 in-place로 UPDATE하지 않습니다. 수정은 구 Context 삭제와 신 Context 생성의 조합이고, 두 동작은 한 Core 트랜잭션에서 처리됩니다. 근거는 공용 계약 `static/05_AI_설계.md` §4.2와 §5.5에 있습니다.

| 문서 | 내용 |
|---|---|
| [`ai/ai-integration.md`](ai/ai-integration.md) | FastAPI Client 구성, 호출 시점, 타임아웃, 내부 인증, 호출 실패 시 동작 |
| [`ai/context-state-sync.md`](ai/context-state-sync.md) | Context 생성 동기화·삭제 동기화와 그 조합인 수정, `ai.context_ai_state` 트랜잭션 |
| [`ai/ai-rescan-scheduler.md`](ai/ai-rescan-scheduler.md) | 5분 주기 재스캔 Scheduler, 만료 판정, 후보 잠금, FAILED Finalizer |
| [`ai/deletion-cancellation.md`](ai/deletion-cancellation.md) | Context·Record 삭제, 수정으로 인한 구 Context 취소, 회원 탈퇴 시 AI 파생 데이터 처리 |
| [`ai/ai-response-assembly.md`](ai/ai-response-assembly.md) | Keyword Visibility에 따른 응답 조립과 검색 결과 Core 재검증 |

## Feed 추천 — `feed/`

Feed는 Spring 단독 기능이며 요청 시 FastAPI·LLM·Embedding API를 호출하지 않습니다.

| 문서 | 내용 |
|---|---|
| [`feed/feed-recommendation.md`](feed/feed-recommendation.md) | Feed 추천 파이프라인 전체 구조와 Spring 단독 처리 경계 |
| [`feed/feed-event.md`](feed/feed-event.md) | `core.feed_event` 테이블 설계와 IMPRESSION / CLICK / SAVE 수집 |
| [`feed/feed-profile-cache.md`](feed/feed-profile-cache.md) | 관심 Profile·Collection 특징 Redis Cache와 TTL, stale 방어 |
| [`feed/feed-scoring.md`](feed/feed-scoring.md) | 후보 채널, 점수 공식, 가중치 설정값, 다양성, Cold Start |
| [`feed/feed-tests.md`](feed/feed-tests.md) | Feed 테스트 항목과 공용 검증 시나리오 중 back 소관 항목 |

## 공용 문서 참조

| 문서 | 관계 |
|---|---|
| `Team-PinLog/docs` `static/05_AI_설계.md` | AI 공용 계약 (단일 원본) |
| `Team-PinLog/docs` `draft/06_데이터모델_및_무결성.md` | `core` 스키마와 핵심 트랜잭션 |
| `Team-PinLog/docs` `static/06_파트간_요구사항.md` | Backend가 Infra·Front에 요구하는 사항 |
| `Team-PinLog/ai/docs` | FastAPI 내부 구현 |
