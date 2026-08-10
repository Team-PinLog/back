# BI-16. 요청 입력 크기 상한 — recordIds 100개, Context 본문 500자

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: Jira 작업, [BD-04](../decisions/BD-04-cursor-pagination.md)(`CursorPage.MAX_SIZE` 방어 상한 선례), [docs#19](https://github.com/Team-PinLog/docs/pull/19)

## 증상

`recordIds` 배열과 Context 본문에 크기 상한이 없었다. 목록 조회는 `CursorPage.MAX_SIZE`(100)로 방어 상한을 두면서 이 둘만 무제한이라 "서버 방어 상한이 얼마인가"에 답이 둘이었다.

- `recordIds`는 Collection 행을 잠근 상태에서 건당 INSERT를 돌기 때문에, 큰 배열 하나가 그 Collection의 다른 요청을 오래 막는다.
- Context 본문은 그대로 임베딩 입력이 되어 호출 비용과 직결된다. 데이터모델 8장이 미확정 항목으로 올려 둔 값이었다.

## 산출

- **`global/common/InputLimits`** — `RECORD_IDS_MAX`(100)·`CONTEXT_BODY_MAX`(500). 값을 DTO마다 흩뿌리지 않고 한 곳에 모았다. `@Size(max = ...)`가 컴파일 타임 상수를 요구하므로 `static final int`다.
- `@Size` 적용 5곳:

  | DTO | 필드 | 상한 |
  |---|---|---|
  | `CollectionCreateRequest` | `recordIds` | 100 |
  | `CollectionAddRecordsRequest` | `recordIds` | 100 |
  | `RecordCreateRequest` | `contextBody` | 500 |
  | `ContextCreateRequest` | `body` | 500 |
  | `ContextUpdateRequest` | `body` | 500 |

- 초과 요청은 Bean Validation이 잡아 400 `INVALID_INPUT` + `fieldErrors`로 나간다. 기존 전역 핸들러 경로를 그대로 타므로 예외·핸들러 추가는 없다.

## 티켓 범위와 다른 점 (Jira 댓글 기록)

티켓 상세는 `@Size` 대상으로 **DTO 넷**(`CollectionCreateRequest`·`CollectionAddRecordsRequest`·`ContextCreateRequest`·`ContextUpdateRequest`)만 적었다. 구현은 **`RecordCreateRequest.contextBody`까지 다섯 곳**에 적용했다.

`POST /records`의 `contextBody`도 그대로 Context가 되기 때문이다. 넷만 막으면 그 경로로 501자를 넣어 **상한을 우회할 수 있다.** 티켓의 목적("Context 본문은 그대로 임베딩 입력이 되어 비용과 직결")을 지키려면 본문이 들어오는 경로 셋을 모두 막아야 한다. 완료 조건은 그대로 전부 충족한다.

## 왜 상수를 한 곳에 모았나

500이 세 DTO에, 100이 두 DTO에 들어간다. 리터럴로 흩뿌리면 한 곳만 바뀌어 **엔드포인트마다 상한이 달라지는** 상태가 조용히 생긴다. 이 레포에서 같은 성격의 사고가 이미 있었다 — envelope 판정이 런타임 advice와 문서 생성기 두 곳에 각자 있어서 결론이 갈렸다(Jira 작업).

`recordIds` 상한은 `CursorPage.MAX_SIZE`와 같은 값으로 정했다. **코드로 묶지는 않았다** — `global/common`이 `global/response`를 참조하는 방향이 되고, 두 값이 같아야 할 본질적 이유가 있는 것은 아니라서다. 대신 javadoc에 근거를 적었다. 감수하는 것: 한쪽만 바뀌면 두 기준이 갈린다.

## 검증

- **RED** — 세 테스트가 실패했고, 실패 내용이 원인을 그대로 보여줬다.

  ```
  createWithMoreThan100RecordIdsIs400       Status expected:<400> but was:<404>
  addRecordsWithMoreThan100RecordIdsIs400   Status expected:<400> but was:<404>
  contextBodyLongerThan500CharsIs400...     Status expected:<400> but was:<201>
  ```

  404는 검증이 없어 소유권 검사까지 내려갔다는 뜻이고, 201은 501자가 그냥 저장됐다는 뜻이다.
- **GREEN** — 위 셋 통과. 400의 `error.fieldErrors[0].field`가 각각 `recordIds`·`contextBody`·`body`인 것까지 단정한다.
- **경계값** — `createWithExactly100RecordIdsSucceeds`(실제 Record 100개로 201 + `recordCount` 100), `contextBodyOfExactly500CharsSucceeds`(500자로 201). 두 테스트는 수정 전에도 통과했으며, 상한을 99나 499로 잘못 넣으면 실패한다.
- 배열 상한 테스트는 **존재하지 않는 id**를 쓴다. 검증이 소유권 검사보다 먼저 걸러지는 것을 보이려면 그게 충분하고(400 vs 404로 갈린다), Record 100개를 만들 필요가 없어 빠르다.
- `./gradlew clean check --no-daemon` 통과(BUNDLE LINE·BRANCH 80% 커버리지 게이트 포함).

## 후속

프론트가 입력 UI에서 같은 값으로 막아야 한다 — 서버만 막으면 사용자가 긴 글을 다 쓴 뒤에 거절당한다. 파트 간 요구사항 05-1 §1.5로 등록했고([docs#19](https://github.com/Team-PinLog/docs/pull/19)), 통지는 [docs#18](https://github.com/Team-PinLog/docs/issues/18)에 있다.
