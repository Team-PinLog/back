# BI-14. 같은 장소 동시 저장을 충돌 없는 INSERT로 수렴시킨다

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: Jira 작업, [BD-12](../decisions/BD-12-duplicate-record-idempotent.md), [BD-19](../decisions/BD-19-place-snapshot.md)

## 증상

`POST /v1/records`에 같은 장소 저장 요청이 동시에 들어오면 500 `INTERNAL_ERROR`가 나갔다. 두 트랜잭션이 `findByMemberIdAndPlaceId`로 나란히 "내 활성 Record 없음"을 보고 둘 다 INSERT해 `uq_record_active`를 위반했고, `DataIntegrityViolationException`이 전역 핸들러의 catch-all까지 올라갔다.

**Place가 이미 커밋돼 있을 때만 재현된다.** Place가 없으면 `PlaceRepository.insertIfAbsent`의 `ON CONFLICT DO NOTHING`이 미커밋 키에서 블로킹해 두 트랜잭션이 통째로 직렬화되고, 뒤늦게 깨어난 쪽은 기존 Record를 찾아 `CONTEXT_ADDED`로 빠진다. 즉 그동안 이 경로가 안전해 보였던 것은 **Place upsert의 부수 효과**였지 의도한 방어가 아니었다.

운영에서는 Place가 이미 있는 쪽이 일반 케이스다 — 다른 사용자가 이미 저장한 장소이거나, 같은 장소를 다시 저장하는 경우.

## 산출

- **`RecordRepository.insertIfAbsent(memberId, placeId)`** — `INSERT ... ON CONFLICT (member_id, place_id) WHERE deleted_at IS NULL DO NOTHING`. `uq_record_active`가 활성행 부분 유니크라 인덱스 술어를 함께 적어야 Postgres가 대상 인덱스를 추론한다.
- **`RecordService.create` 단일 경로화** — 조회로 분기하던 두 갈래(`createNewRecord` / `addContextToExisting`)를 없앴다. 이제 먼저 INSERT를 시도하고 **영향 행 수로 분기한다.**

  ```text
  insertIfAbsent == 1 → 이 요청이 Record를 만들었다 → RECORD_CREATED (201)
  insertIfAbsent == 0 → 활성 Record가 이미 있었다   → CONTEXT_ADDED  (200)
  ```

  어느 쪽이든 재조회한 Record에 요청의 `contextBody`를 Context로 붙인다. 그래서 **경합에 진 요청의 본문도 사라지지 않는다** — 재조회 결과만 돌려주면 사용자는 500도 못 보고 저장됐다고 믿는데 본문이 없다.

## 왜 예외를 잡지 않았나 (Jira 댓글 기록)

티켓 상세는 "`uq_record_active` 위반을 **잡아** 기존 Record를 재조회"였다. 구현은 충돌 자체를 없애는 쪽으로 갔다.

JPA에서 제약 위반이 나면 그 트랜잭션은 rollback-only로 표시되어, 같은 트랜잭션 안에서 재조회·Context 추가를 이어갈 수 없다. 잡으려면 트랜잭션을 새로 열어야 하고, 자기 호출은 프록시를 타지 않으므로 별도 빈이나 self-injection이 필요하다. 정상 흐름 하나를 위해 트랜잭션 경계가 둘로 늘어난다.

`ON CONFLICT DO NOTHING`은 이 레포에 이미 있는 패턴이고(데이터모델 6.1의 Place upsert), 예외 경로 자체가 생기지 않아 순차·동시 요청이 같은 코드로 수렴한다. 완료 조건은 그대로 전부 충족한다.

**감수하는 것** — 네이티브 INSERT라 `@CreatedDate`·`@LastModifiedDate` 감사 리스너가 개입하지 않고 DB `now()`가 시각을 채운다. `place`가 이미 같은 방식이고 두 컬럼 모두 `NOT NULL DEFAULT now()`라 값은 항상 채워진다. 다만 **Record 생성 시각의 출처가 애플리케이션에서 DB로 바뀌었다** — 두 시계가 어긋나면 `record.created_at`과 같은 트랜잭션의 `context.created_at`이 미세하게 다를 수 있다. 같은 인스턴스라 실질 영향은 없다고 보고 넘어간다.

## 검증

- **RED** — `concurrentCreateOnExistingPlaceAddsContextInsteadOfFailing`이 수정 전 `[201, 500]`(기대 `[201, 200]`)로 실패. `RecordApiTests` 16건 중 정확히 이 1건만 실패.
- **GREEN** — 같은 테스트 통과. 응답 `result`가 `CONTEXT_ADDED`이고, 두 요청의 `recordId`가 같으며, 활성 Context 본문이 `[동시 요청 A, 동시 요청 B]` 둘 다 남는 것까지 단정한다.
- **회귀** — `concurrentCreateOnNewPlaceStaysSerialized`로 Place 없는 경로의 직렬화가 유지되는지 고정했다. 수정 전에도 통과하던 경로이며, 이번 변경이 그 동작을 깨지 않는지 지킨다. 순차 케이스(`secondCreateForSamePlaceAddsContextAndReturns200`)와 Place 스냅샷 미갱신(`createRecordReusesExistingPlaceSnapshotWithoutUpdating`)도 그대로 통과.
- `./gradlew clean check --no-daemon` 통과(103이 넣은 BUNDLE LINE·BRANCH 80% 커버리지 게이트 포함).

## 남은 것

`POST /v1/follows`의 같은 성격의 경합(`uq_follow_active` 위반 → 500, 409 `DUPLICATE_FOLLOW`가 있는데도)은 별도 티켓 Jira 작업다. Follow는 되돌릴 Record가 없어 이 패턴이 그대로 적용되지 않는다 — 중복이면 기존 Follow를 돌려주는 게 아니라 409로 거절해야 하므로, 그쪽은 예외를 잡아 변환하는 편이 맞다.
