# PinLog Backend — AI 파트 문서

Spring 측 **AI 연동**과 **Feed 추천**의 설계·결정·구현 기록입니다. AI 파트 소유이며, 공용 계약의 단일 원본은 `Team-PinLog/docs`의 `static/05_AI_설계.md`입니다(여기는 Spring 구현 방법만 다룹니다).

모든 AI 연동 문서를 관통하는 전제:

```text
동일한 context_id는 항상 동일한 Context 본문을 의미한다.
```

## 편집 경계

이 구역은 AI 파트가 작성·관리합니다. back 코드 변경에 수반되는 문서 갱신은 백엔드도 편집할 수 있습니다. 단 [`troubleshooting/`](troubleshooting/)·[`implements/`](implements/)는 **기록 보존 구역**이라 삭제 대신 **상태 갱신**으로 처리해 주세요(회고·복기 목적). `spec/`은 유효 명세라 이 제약의 대상이 아닙니다.

## 구역

| 구역 | 내용 |
|---|---|
| [`spec/`](spec/) | 설계·구현 명세 — "무엇을 만들 것인가" |
| [`proposals/`](proposals/) | 제안·결정(P 번호) + 미결 — "왜 그렇게 정했나" |
| [`implements/`](implements/) | 구현 리포트 — "어떻게 만들었나" |
| [`troubleshooting/`](troubleshooting/) | 문제 해결 |
| [`WORKLOG.md`](WORKLOG.md) | 시간순 작업 로그 |

## spec (평면)

- **AI 연동**: [ai-integration](spec/ai-integration.md) · [context-state-sync](spec/context-state-sync.md) · [ai-rescan-scheduler](spec/ai-rescan-scheduler.md) · [deletion-cancellation](spec/deletion-cancellation.md) · [ai-response-assembly](spec/ai-response-assembly.md)
- **Feed 추천**: [feed-recommendation](spec/feed-recommendation.md) · [feed-event](spec/feed-event.md) · [feed-profile-cache](spec/feed-profile-cache.md) · [feed-scoring](spec/feed-scoring.md) · [feed-tests](spec/feed-tests.md)

> `spec/`은 아직 코드가 없는 **구현 예정 명세**입니다. Feed는 Spring 단독 기능이며 요청 시 FastAPI·LLM·Embedding을 호출하지 않습니다.
