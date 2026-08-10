# BI-15. 중복 팔로우 경합을 500이 아니라 409로 거절한다

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: Jira 작업, [BI-14](BI-14-2026-07-28-record-create-conflict-free-insert.md)(같은 성격의 Record 쪽 경합)

## 증상

`POST /v1/follows`에 같은 책장 팔로우 요청이 동시에 들어오면 500 `INTERNAL_ERROR`가 나갔다. `findByFolloweeMemberIdAndFollowerMemberId`로 중복을 확인하고 `save`하는 사이가 원자적이지 않아, 두 트랜잭션이 나란히 "중복 아님"으로 판정하고 둘 다 INSERT해 `uq_follow_active`를 위반했다.

**409 `DUPLICATE_FOLLOW`가 `ErrorCode`에 이미 있는데도 500이 나갔다.** `DataIntegrityViolationException`이 전역 핸들러의 catch-all `Exception` 분기까지 올라갔기 때문이다.

## 산출

- `FollowService.follow` — 저장을 `saveAndFlush`로 바꿔 위반이 커밋 시점이 아니라 이 자리에서 드러나게 하고, `DataIntegrityViolationException`을 잡아 `DuplicateFollowException`(409)으로 바꾼다.
- **사전 확인은 그대로 둔다.** 흔한 순차 요청을 예외 없이 거르는 경로이고, 새로 넣은 catch는 경합에서 진 요청만 받는 안전망이다. 둘은 대체 관계가 아니다.
- **제약 이름으로 좁힌다** — `uq_follow_active` 위반만 409로 바꾸고 나머지는 그대로 올린다. 모든 `DataIntegrityViolationException`을 409로 뭉뚱그리면 성격이 다른 위반(예: FK)이 "이미 팔로우한 책장입니다"로 나가 원인을 가린다.

  Hibernate `ConstraintViolationException.getConstraintName()`이 **부분 유니크 인덱스 이름도 돌려주는지**가 이 방식의 전제였다. 가정하지 않고 테스트로 확인했다 — 돌려주지 않았다면 rethrow되어 500이 유지되고 테스트가 실패한다.

## 왜 105와 다른 방식인가

같은 "확인 후 저장" 경합이지만 처방이 갈린다.

| | Jira 작업 (Record) | Jira 작업 (Follow) |
|---|---|---|
| 원하는 결과 | 기존 Record에 Context 추가(멱등) | **거절**(409) |
| 위반 뒤 DB 작업 | 필요함 — 재조회 + Context INSERT | 없음 — 예외만 바꿔 던짐 |
| 처방 | `ON CONFLICT DO NOTHING`으로 충돌 제거 | 위반을 잡아 예외 변환 |

105에서 잡는 방식을 못 쓴 이유는 제약 위반이 트랜잭션을 rollback-only로 만들어 **같은 트랜잭션 안에서 재조회·INSERT를 이어갈 수 없어서**다. 104는 위반 뒤에 DB를 더 건드리지 않고 예외만 바꿔 던지므로 그 제약에 걸리지 않는다. 반대로 104에 `ON CONFLICT DO NOTHING`을 쓰면 중복 요청이 조용히 성공한 것처럼 보여 명세 8.2의 "활성 중복 Follow 확인"과 어긋난다.

## 검증

- **RED** — `concurrentDuplicateFollowIs409NotServerError`가 수정 전 `[500, 201]`(기대 `[201, 409]`)로 실패. `FollowApiTests` 13건 중 이 1건만 실패.
- **GREEN** — 13건 전부 통과. 409 응답의 `error.code`가 `DUPLICATE_FOLLOW`이고 활성 Follow 행이 정확히 1건인 것까지 단정한다.
- RED가 `[500, 201]`이고 GREEN이 `[201, 409]`라는 대조가 **새 catch 경로가 실제로 실행됐다는 근거**다. 경합이 일어나지 않고 사전 확인이 잡았다면 RED에서도 `[201, 409]`가 나와 처음부터 통과했을 것이다.
- 순차 케이스(`duplicateFollowIs409WithoutDuplicateRow`)와 자기 팔로우 422, 탈퇴 followee 제외 등 기존 12건 그대로 통과.
- `./gradlew clean check --no-daemon` 통과(BUNDLE LINE·BRANCH 80% 커버리지 게이트 포함).
