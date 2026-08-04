# 지도 마커 응답에 가장 최근에 담긴 컬렉션 id를 더한다

- **날짜**: 2026-08-04
- **추적**: S15P11A705-308
- **관련**: [docs#48](https://github.com/Team-PinLog/docs/pull/48) (08 §4.2 계약 선행 개정)

프론트가 지도 마커 색상을 레코드가 담긴 컬렉션 기준으로 구분하기로 해서, `GET /v1/records/map` 응답 `items`의 각 마커에 `latestCollectionId`를 실었다. 값은 그 Record가 담긴 활성 연결(`collection_record`) 중 **담은 시각 최신**(`created_at DESC`, 동시각이면 `id DESC`) 기준 Collection id — 컬렉션 내부 정렬(데이터모델 2.7)과 같은 기준이라 "컬렉션에서 보이는 최신"과 "마커 색"이 어긋나지 않는다. 어느 컬렉션에도 담기지 않은 Record는 `null`이고, 컬렉션에서 뺀(소프트 삭제) 연결은 판단에서 제외된다.

구현 판단 둘:

1. **마커 쿼리에 조인하지 않고 별도 배치 질의로 채웠다.** "record별 최신 1건"은 `DISTINCT ON`이 필요해 JPQL로는 안 되고, 기존 마커 JPQL을 native로 갈아엎는 것보다 `RecordLatestCollectionRepository`(NamedParameterJdbcTemplate, `ContextKeywordRepository`·`CollectionFirstPageRepository`와 같은 패턴) 한 번을 더 부르는 쪽이 변경 반경이 작다. 마커마다 반복 조회하면 N+1이라 record id 목록을 IN으로 묶어 한 번에 가져온다.
2. **native라 소프트 삭제 제외를 쿼리에 직접 적었다.** `@SQLRestriction`은 native 쿼리에 적용되지 않으므로 `deleted_at IS NULL`을 빼먹으면 뺀 연결이 최신 판단에 되살아난다 — 제거 링크 제외를 테스트로 고정했다.

`MapMarkerResponse`는 6번째 컴포넌트로 `latestCollectionId`를 얻었고, 마커 JPQL은 5개 인자 보조 생성자를 그대로 쓴다. bbox·keyword 경로 모두 질의 이후 한 지점에서 채우므로 경로별 분기가 없다.
