# 지도 bbox 안 상위 키워드 조회 API를 더했다

- **날짜**: 2026-08-07
- **추적**: Jira 작업
- **관련**: [BI-42](../implements/BI-42-2026-08-07-map-keyword-chips.md) · [BD-13](../decisions/BD-13-public-boundary-query-dto-split.md)

지도 화면 검색창 밑 키워드 칩을 위해 `GET /v1/records/map/keywords`를 더했다. bbox 안 내 Record
기준으로 키워드를 Record 단위로 세어 상위 5개를 돌려준다. 마이그레이션은 없다.

## 마커 엔드포인트와 분리한 것

`GET /v1/records/map`에 얹지 않고 별도 엔드포인트로 뒀다. 대신 두 요청의 완료 순서가 보장되지
않아, 빠르게 패닝하면 칩은 bbox A, 마커는 bbox B의 결과가 화면에 같이 뜰 수 있다. 프론트가 요청
토큰을 들고 늦게 도착한 응답을 버려야 하는 계약을 PR 본문에 명시했다.

## 칩이 반영하지 않는 것

칩은 bbox만 반영한다. 장소명 검색어(`keyword`)도, 적용 중인 키워드 필터(`keywordId`, 다음
티켓 Jira 작업)도 반영하지 않는다. 적용 중인 필터를 반영하면 그 키워드를 뺀 나머지 칩이 전부
0이 되어 사라지고, 사용자가 다른 칩으로 갈아탈 방법이 없어진다.

## bbox 생략 경로를 남긴 이유와 그 한계

지도 화면은 항상 bbox를 보내므로 생략 경로는 실제로는 쓰이지 않지만, `/records/map`과 파라미터
계약을 맞추기 위해 남겼다. 이 경로는 회원당 Context 3,000 이하 전제 안에서만 안전하다(11.6ms) — 그
상한을 넘는 회원이 나오면 계획 역전이 먼저 흔들리는 자리가 여기다. bbox가 있는 경로는 별도 SQL이라
같은 위험이 없다.

## 동점 정렬을 `display_name`이 아니라 `keywordId`로 끊은 것

`display_name`으로 끊으면 한글 collation에 따라 운영 DB와 테스트 컨테이너가 다른 순서를 낼 수
있다. `RecordService.sortByName`이 마커 정렬에서 이미 피해 간 함정과 같은 것이라, 이번에는 애초에
DB collation에 기대지 않는 키(`keywordId`)를 골랐다.

## 캐시·집계 테이블을 만들지 않기로 한 것

측정상 성능은 이미 충분하다(BI-42 6장 근거). 만들지 않은 진짜 이유는 정합성이다 —
`ai.context_keyword`의 INSERT 주체가 FastAPI라 백엔드에 증분 집계를 걸 지점이 없고, 만들면
가시성이 `BLOCKED`로 바뀌어도 재계산 전까지 계속 노출하는 스냅샷 문제가 생긴다. 재검토 조건은
회원당 Context 3,000 초과 + p95 실측 악화이며, 그때도 첫 수단은 집계 테이블이 아니라 Redis TTL
캐시로 정했다.

## 테스트를 네 단계로 나눠 쌓은 것

구현(`49095af`) → Record 단위 중복 제거와 동점 정렬(`3221de8`) → bbox 생략 경로(`e5a0d51`) →
소유권 격리와 빈 결과 계약(`5d047f5`) 순으로 커밋을 나눴다. 마지막 단계(소유권·soft-delete·5개
미만·빈 배열)는 Task 1의 SQL이 이미 `WHERE r.member_id = :memberId`와
`ct.deleted_at IS NULL`을 갖고 있어 테스트만 추가하고 SQL은 고치지 않았다 — 회귀를 미리 잡아 둔
설계였다는 뜻으로 남긴다.
