# PinLog Backend 구현 문서

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

이 디렉터리는 Spring Backend가 담당하는 **AI 연동**과 **Feed 구현**의 세부 명세를 관리합니다.

공용 계약(원칙, 상태값, 내부 API, Visibility 정의, AI 데이터 구조)은 여기에 복사하지 않습니다. 항상 `static/05_AI_설계.md`가 단일 원본이며, 이 디렉터리의 문서는 그 계약을 Spring에서 **어떻게 구현할지**만 기술합니다. 두 문서가 충돌하면 공용 계약이 우선입니다.

AI 연동 문서 전체를 관통하는 전제는 하나입니다.

```text
동일한 context_id는 항상 동일한 Context 본문을 의미한다.
```

Context는 불변 엔티티이며 본문을 in-place로 UPDATE하지 않습니다. 수정은 구 Context 삭제와 신 Context 생성의 조합이고, 두 동작은 한 Core 트랜잭션에서 처리됩니다. 근거는 공용 계약 §4.2와 §5.5에 있습니다.

## AI 연동 — `ai/`

| 문서 | 내용 |
|---|---|
| [`ai/ai-integration.md`](ai/ai-integration.md) | FastAPI Client 구성, 호출 시점, 타임아웃, 내부 인증, 호출 실패 시 동작 |
| [`ai/context-state-sync.md`](ai/context-state-sync.md) | Context 생성 동기화·삭제 동기화와 그 조합인 수정, `ai.context_ai_state` 트랜잭션 |
| [`ai/ai-rescan-scheduler.md`](ai/ai-rescan-scheduler.md) | 5분 주기 재스캔 Scheduler, 만료 판정, 후보 잠금, FAILED Finalizer |
| [`ai/deletion-cancellation.md`](ai/deletion-cancellation.md) | Context·Record 삭제, 수정으로 인한 구 Context 취소, 회원 탈퇴 시 AI 파생 데이터 처리 |
| [`ai/ai-response-assembly.md`](ai/ai-response-assembly.md) | Keyword Visibility에 따른 응답 조립과 검색 결과 Core 재검증 |

## Feed — `feed/`

| 문서 | 내용 |
|---|---|
| [`feed/feed-recommendation.md`](feed/feed-recommendation.md) | Feed 추천 파이프라인 전체 구조와 Spring 단독 처리 경계 |
| [`feed/feed-event.md`](feed/feed-event.md) | `core.feed_event` 테이블 설계와 IMPRESSION / CLICK / SAVE 수집 |
| [`feed/feed-profile-cache.md`](feed/feed-profile-cache.md) | 관심 Profile·Collection 특징 Redis Cache와 TTL, stale 방어 |
| [`feed/feed-scoring.md`](feed/feed-scoring.md) | 후보 채널, 점수 공식, 가중치 설정값, 다양성, Cold Start |
| [`feed/feed-tests.md`](feed/feed-tests.md) | Feed 테스트 항목과 공용 검증 시나리오 중 back 소관 항목 |

## 관련 문서

| 문서 | 관계 |
|---|---|
| Team-PinLog/docs `static/05_AI_설계.md` | AI 공용 계약 (단일 원본) |
| Team-PinLog/docs `draft/06_데이터모델_및_무결성.md` | `core` 스키마와 핵심 트랜잭션 |
| Team-PinLog/ai/docs | FastAPI 내부 구현 |
