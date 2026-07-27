# BD-16. AI 파생 데이터는 물리 삭제 대신 즉시 무효화 표시 — 백엔드가 직접 쓴다

- **상태**: Accepted
- **날짜**: 2026-07-27 (`Team-PinLog/docs` `a0c20c2` AI 계약 정합성 정정)
- **작성 시점**: 2026-07-27 — 결정 이후에 정리
- **관련**: S15P11A705-76
- **공용 계약**: [06_데이터모델_및_무결성 §1.1·§1.3·§6.6](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md) · [05_AI_설계 §6.5~6.6·§9.4·§11](https://github.com/Team-PinLog/docs/blob/main/static/05_AI_설계.md)

## 맥락

Context가 삭제되거나 수정되면 그 Context의 임베딩·Keyword가 남는다. 두 가지를 정해야 했다.

1. **파생 데이터를 어떻게 없애는가** — 사용자 데이터처럼 소프트 삭제할 것인가, 물리 삭제할 것인가, 다른 방식이 필요한가
2. **누가 없애는가** — `ai` 스키마는 AI 파트 소유인데 Context를 지우는 트랜잭션은 백엔드가 연다

여기에 타이밍 문제가 겹친다. AI 워커가 처리 중일 때 사용자가 Context를 지우면 뒤늦게 도착한 결과가 고아 데이터로 남는다. Context는 불변이라 버전 컬럼이 없으므로([BD-07](BD-07-context-immutability.md)) 그것으로는 막을 수 없다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) `core`와 동일한 소프트 삭제 | 규칙이 하나로 통일됨([BD-08](BD-08-soft-delete-no-restore.md)) | 진행 중인 AI 작업을 멈추지 못한다. 삭제 표시만으로는 워커가 계속 쓴다 |
| (b) 즉시 물리 `DELETE` | 행이 남지 않아 개인정보가 즉시 사라진다 | 늦게 도착하는 결과를 막을 근거가 함께 사라진다. 물리 삭제 시점을 개인정보 정책과 분리해 정할 여지가 없다 |
| **(c) 즉시 무효화 표시** | 진행 중 작업 취소와 조회 제외를 각각 담당하는 축을 둘 수 있다 | 행이 남는다. 물리 삭제 시점을 따로 정해야 한다 |

## 결정

**(c)를 채택한다. 능동적 선택.** 무효화는 **두 축**으로 표시한다. 하나로는 두 가지 일을 못 하기 때문이다.

```text
ai.context_ai_state 의 두 status → CANCELLED   -- 진행 중인 AI 작업을 취소
ai.context_embedding.is_deleted  → true        -- 검색 제외 + 물리 삭제 대상 식별
```

**물리 삭제 시점은 이 결정의 범위가 아니다.** 별도 개인정보 정책을 따른다.

**백엔드가 같은 트랜잭션에서 직접 쓴다.** `core`와 `ai`가 동일 PostgreSQL 인스턴스의 스키마 분리라 단일 트랜잭션이 가능하다. AI 워커에 통지하고 위임하면 통지가 유실될 때 원자성이 깨진다.

쓰기 권한은 이렇게 나뉜다.

| 테이블 | 백엔드 | AI 워커 |
|---|---|---|
| `core.*` | O | X |
| `ai.context_ai_state` | 최초 생성 · `PENDING` · `CANCELLED` · `retry_count` · 재시도 소진 Finalizer `FAILED` | `PROCESSING` · `COMPLETED` · 작업 중 오류 `FAILED` |
| `ai.context_embedding` | **`is_deleted`만 UPDATE** | INSERT / UPDATE (`is_deleted` 제외) |
| `ai.context_keyword` | **X** | INSERT / DELETE |
| `ai.context_keyword_analysis` | X | INSERT / UPDATE |
| `ai.keyword_preset` | X | O |

백엔드는 `ai.context_keyword`를 **읽기 조인**할 권리를 갖는다. Record·Collection·Feed 조회에서 공개 Keyword를 제공해야 하기 때문이다([BD-18](BD-18-keyword-preset-and-visibility.md)).

## 결과

**감수하는 것**

- **파트 경계에 의도된 구멍이 있다.** 백엔드가 `ai` 스키마에 쓴다. 다만 범위는 좁다 — `context_ai_state`의 취소 전이와 `context_embedding.is_deleted`뿐이고 `context_keyword`에는 쓰지 않는다.
- **행이 남는다.** 무효화 표시일 뿐 물리 삭제가 아니므로 개인정보가 DB에 남는다. 물리 삭제 시점 정책이 없으면 **무기한 남는다.** [BD-08](BD-08-soft-delete-no-restore.md)의 보존 기간 미정 문제와 같은 뿌리다.
- **늦게 도착한 결과 차단이 State에 의존한다.** 워커는 대상 단계가 `PROCESSING`일 때만 결과를 저장한다. 백엔드가 `CANCELLED` 전이를 빠뜨리면 고아 데이터가 생긴다. 버전 컬럼을 대신하는 유일한 방어 지점이다.
- **영향 행 0이 정상이다.** 임베딩이 아직 없는 Context를 지우면 `is_deleted` UPDATE가 0행을 갱신한다. 오류가 아니며 `CANCELLED`가 이후 INSERT를 막는다.

**재검토 트리거**

- **MVP는 정확 cosine 검색이며 ANN 인덱스(HNSW 등)를 두지 않는다.** 검색 제외는 `is_deleted = false` 필터가 담당한다. ANN을 도입하면 무효화 행이 인덱스를 부풀리므로 **물리 삭제 주기를 그때 함께 정해야 한다.**
- 물리 삭제 정책이 정해지면 → 삭제 주체와 주기를 이 문서에 연결한다.
- `ai` 스키마가 별도 인스턴스로 분리되면 → 단일 트랜잭션 전제가 깨진다. 통지 방식으로 옮기고 유실 대비 정리 배치를 함께 설계해야 한다.
