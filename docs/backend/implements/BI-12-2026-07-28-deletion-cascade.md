# BI-12. Record/Context/Collection 삭제와 연쇄 트랜잭션

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-70, [BD-11](../decisions/BD-11-minimum-holding-invariants.md), [BD-08](../decisions/BD-08-soft-delete-no-restore.md), [BD-09](../decisions/BD-09-no-record-update-path.md)

## 산출

- **오류 계약 확장** — `ErrorCode.DELETE_CONFIRMATION_REQUIRED`(409)와
  `ErrorResponse.Impact(recordDeleted, collectionIds)`. impact는 NON_NULL 선택 필드로, 명세 1.5의
  "일부 code는 error에 추가 필드를 더한다"를 구현한다. `DeleteConfirmationRequiredException`이
  impact를 실어 나르고 `GlobalExceptionHandler`가 전용 분기로 직렬화한다.
- `DELETE /v1/records/{recordId}/contexts/{contextId}` — Record 잠금 후 활성 Context ≥2면 소프트
  삭제(204), 1이면 409 + impact(recordDeleted=true, 이 Record가 마지막인 collectionIds).
- `DELETE /v1/records/{recordId}` — 마지막인 활성 Collection이 있으면 409 + impact. 없으면
  Record·Context·연결 소프트 삭제와 남은 Collection의 record_count 감소를 한 트랜잭션으로.
- `DELETE /v1/records/{recordId}/force` — 마지막이던 Collection까지 소프트 삭제. 연쇄 대상이
  없어도 정상 수행(일반 삭제와 동일 결과).
- `DELETE /v1/collections/{collectionId}/records/{recordId}` — Collection 잠금, 활성 연결 ≥2면
  연결만 제거 + record_count 감소, 1이면 409 + impact(recordDeleted=false).
- `DELETE /v1/collections/{collectionId}` — Collection·연결 소프트 삭제, 원본 Record 유지.
- **잠금 구조** — 활성 수를 세고 분기한 뒤 쓰는 모든 경로는 부모 행(PESSIMISTIC_WRITE)을 잠근다.
  Record 삭제가 Collection의 record_count를 만질 때는 해당 Collection도 잠근다(6.7과 같은 경합).
  잠금 순서는 Record → Collection 단방향이라 교착이 없다(addRecords는 Collection만 잠근다).

  > **정정 (2026-07-31, S15P11A705-201).** 위 마지막 문장의 근거가 불완전하다. "Record → Collection
  > 단방향"은 **타입 사이의 순서만** 논증하고 **Collection 사이의 순서는 다루지 않는다.** 서로 다른
  > Record를 지우는 두 트랜잭션이 같은 Collection 둘을 반대 순서로 잡으면 교착이 날 수 있고, 그
  > 순서를 정하는 것은 `cascadeDelete`가 부르는 역조회 쿼리다.
  >
  > 작성 시점에 교착이 실제로 없었던 이유는 이 논증이 아니라, 실행 계획이
  > `uq_colrec_active (collection_id, record_id)`를 훑어 `collection_id` 순서를 우연히 돌려줬기
  > 때문이다(두 Record를 반대 순서로 담아 25회 동시 삭제해도 전부 204). 쿼리가 준 보증이 아니므로
  > 테이블이 커져 bitmap heap scan으로 바뀌면 물리 순서가 나온다.
  >
  > 역조회를 `findByRecordIdOrderByCollectionIdAsc`로 바꿔 정렬을 쿼리가 보장하게 했다. 교착이 없는
  > 근거는 이제 **"타입 사이 단방향 + Collection 사이 정렬 고정"** 두 가지다.

## 범위 제외 (Jira 댓글 기록)

- **Record 재생성 시 연결 승계** — 티켓 항목이 정본(데이터모델 2.4 `previous_record_id` 없음,
  6.2 "Collection 연결은 복구하지 않습니다")과 충돌해 구현하지 않았다. 삭제된 Place 재저장이 새
  Record를 만들고 과거 연결이 복구되지 않는 것을 테스트로 고정했다.
- **AI 파생 데이터 무효화** — 백엔드의 ai 스키마 쓰기 연동(상태 최초 생성)이 아직 없어 제외.
  AI 연동 티켓에서 생성·무효화를 한 쌍으로 구현한다.

## 검증

- `DeletionApiTests` 11건 — 위 5개 엔드포인트의 성공·409·impact, 삭제 Place 재저장의 연결 미복구,
  타인 404(5개 경로 전부), 그리고 **동시성**: 활성 Context 2개에 서로 다른 Context 삭제를 동시에
  보내면 정확히 한쪽만 204이고 활성 수가 1로 남는다(Record 행 잠금이 직렬화).
- `./gradlew clean check --no-daemon` 통과.
