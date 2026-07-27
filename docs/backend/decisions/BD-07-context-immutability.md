# BD-07. Context 불변 — 수정은 삭제+생성으로 처리

- **상태**: Accepted
- **날짜**: 2026-07-22 (`Team-PinLog/docs` `c1b2869`)
- **작성 시점**: 2026-07-27 — 결정 이후에 정리
- **관련**: S15P11A705-76 · docs `c1b2869`·`1259316`·`eaeae93`·`4541b8a`(M2 A안)
- **공용 계약**: [05_AI_설계 §4.2·5.3](https://github.com/Team-PinLog/docs/blob/main/static/05_AI_설계.md) · [06_데이터모델_및_무결성 §2.5](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md)

## 맥락

초기 데이터 모델은 Context를 **가변**으로 두고, 도메인 버전 컬럼으로 비동기 AI 결과의 신선도를 판별하는 구조였다.

```text
context.body_version              INT NOT NULL DEFAULT 1   -- body 수정 시에만 증가
ai.context_embedding.context_version
ai.context_keyword.context_version

수정: Context 행 잠금 → body 변경 확인 → body 갱신 + body_version 증가 → AI 상태 PENDING 리셋
저장: 도착한 결과의 context_version이 현재 body_version보다 낮으면 폐기
```

이 구조에는 두 가지 부담이 있었다.

1. **stale 판별 책임이 두 서비스에 걸쳐 있었다.** Spring이 버전을 올리고 FastAPI가 비교·폐기해야 했다. 게다가 JPA `@Version`(낙관적 락)은 어느 컬럼이 바뀌어도 증가하므로 "본문 세대"라는 의미를 낼 수 없어, 도메인 버전을 별도 컬럼으로 따로 관리해야 했다.
2. **경합 방어가 두 갈래였다.** 처리 중 사용자가 Context를 삭제하는 경합(삭제 경합)은 어차피 방어해야 했고, 처리 중 본문이 바뀌는 경합(수정 경합)은 그와 별개의 방어를 요구했다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) `body_version` 가변 모델 유지 | 행이 하나로 유지되어 `contextId`가 안정적. 프론트 계약이 단순 | stale 판별이 Spring·FastAPI 양쪽에 분산. `@Version`과 별도인 도메인 버전을 손으로 관리. 수정 경합과 삭제 경합을 각각 방어 |
| (b) JPA `@Version`(낙관적 락)으로 대체 | 프레임워크가 제공, 직접 관리 불필요 | 본문 외 컬럼이 바뀌어도 증가해 "본문이 바뀌었는가"를 판별하지 못함. 요구와 어긋남 |
| **(c) 불변 — 수정 = 삭제 + 생성** | 판별 **대상 자체가 소멸**해 버전 컬럼이 불필요. 수정 경합이 삭제 경합에 흡수되어 방어 지점이 하나 | `contextId`가 바뀌어 응답 계약에 영향. 삭제 행 누적. 구 임베딩 재사용 불가 |

## 결정

**(c)를 채택한다. 능동적 선택.** 두 가지가 결정적이었다.

1. **버전 판별 로직의 복잡도.** 낡은 결과를 걸러내는 책임이 Spring과 FastAPI에 나뉘어 있었고, 프레임워크가 주는 `@Version`으로는 대체할 수 없어 도메인 버전을 직접 관리해야 했다.
2. **경합 방어 지점 통합.** 본문이 바뀌지 않으면 "수정"은 삭제와 생성의 조합이므로, 수정 경합이 삭제 경합에 흡수된다. 방어를 약화한 것이 아니라 **방어해야 할 가변 상태 자체를 제거**한 것이다.

핵심 불변식:

```text
동일한 context_id는 항상 동일한 Context 본문을 의미한다.
```

**구현 순서 제약** — 신 Context INSERT를 구 Context 삭제보다 **먼저** 수행한다. 활성 Record는 활성 Context를 최소 한 개 가져야 하는데, 삭제를 먼저 하면 Context가 하나뿐인 Record에서 활성 수가 0이 되는 중간 상태가 생긴다. 추가를 먼저 하면 1 → 2 → 1로 움직여 한 번도 0이 되지 않으므로, 수정 경로를 위해 가드를 예외 처리할 필요가 없다. 가드와 잠금의 상세는 [BD-11](BD-11-minimum-holding-invariants.md)에 있다.

수정은 삭제 API를 재사용하지 않고 **별도 유스케이스로 구현**한다. "마지막 Context는 삭제할 수 없다"는 삭제 유스케이스의 규칙이며 수정에는 적용되지 않기 때문이다.

## 결과

**감수하는 것**

- **응답 계약** — 수정 응답에 새 `contextId`를 반환한다. 구 `contextId`를 유지하는 계약은 허용되지 않으며 프론트가 이를 반영해야 한다([05-1_파트간_요구사항 §1.1](https://github.com/Team-PinLog/docs/blob/main/static/05-1_파트간_요구사항.md)).
- **정렬·표시** — `created_at`이 새 행 기준으로 갱신된다(M2 A안, 백엔드 주도). 목록에서 수정한 맥락이 최신으로 올라오는 것은 **의도된 동작이며 버그가 아니다.**
- **AI 비용** — 구 Context의 Embedding·Keyword·State를 승계하지 않는다. 한 글자만 고쳐도 임베딩을 새로 생성한다.
- **저장 증가** — 소프트 삭제된 Context 행이 누적된다. 보존 기간 정책은 아직 없다([06 §8](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md)).
- **일시적 공백** — 수정 직후 새 Context는 Keyword가 비어 있고, 재분석 중에는 자연어 검색에서 일시 제외된다.

**재검토 트리거**

- 최초 작성 시각 보존이나 수정 계보 추적 요구가 생기면 → `origin_context_id` 도입을 재검토한다.
- 소프트 삭제 Context 누적이 조회 성능이나 저장 비용에 실제 영향을 주면 → 하드 삭제 배치와 보존 기간 정책을 함께 결정한다([BD-08](BD-08-soft-delete-no-restore.md)).
- 임베딩 재생성 비용이 문제가 되면 → 본문 해시 기반 재사용을 검토한다. 단, `context_id`와 본문의 1:1 불변식은 유지해야 한다.
