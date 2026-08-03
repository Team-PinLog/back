# Collection이 소유자 자기 Record만 담는다는 불변식을 addRecords 경로에도 고정

- **날짜**: 2026-08-03
- **추적**: S15P11A705-190
- **관련**: `CollectionService.requireAllOwnedActiveRecords` · `CollectionRecordRepository.findByCollectionIdIn`

`collection_record` 링크를 만드는 프로덕션 경로는 `CollectionService.create`와
`CollectionService.addRecords` 두 곳뿐이고, 코드를 확인한 결과 둘 다 이미
`requireAllOwnedActiveRecords`로 소유권을 강제하고 있었다 — 뚫린 경로는 없었다.

다만 테스트는 `create` 경로(`createWithSomeoneElsesRecordIsHiddenAs404`)만 있었고,
`addRecords` 경로에서 **소유자 본인이 남의 Record를 자기 컬렉션에 추가하려는 시도**를
고정하는 테스트가 없었다. 기존 `addRecordsByStrangerIsHiddenAs404`는 낯선 사람이 남의
컬렉션 API 자체를 호출하는 다른 시나리오라 이 케이스를 대신하지 못한다.

`addRecordsWithSomeoneElsesRecordIsHiddenAs404`를 추가해 이 케이스를 404로 고정하고,
`CollectionRecordRepository.findByCollectionIdIn`의 javadoc이 전제로 삼는 불변식이
깨지면 탈퇴 연쇄 삭제(데이터모델 6.9)가 남의 Record 링크를 지우지 못한다는 연결을
테스트 바로 위 주석으로 남겼다. 코드 변경은 없고 회귀 테스트만 추가했다.
