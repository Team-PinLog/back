# BI-10. Collection 생성·조회·편집 API

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-68, [BD-11](../decisions/BD-11-minimum-holding-invariants.md), [BD-23](../decisions/BD-23-collection-auto-publish.md), [BD-04](../decisions/BD-04-cursor-pagination.md)

## 산출

- `POST /v1/collections` — 제목(≤20자)·recordIds(≥1) 검증, 본인 활성 Record가 아니면 404 은닉.
  생성 즉시 자동 발행(`published_at` = `created_at`, 66의 `@PrePersist`). `record_count`는 연결
  생성과 동일 트랜잭션에서 갱신.
- `GET /v1/collections` — 내 목록. `created_at DESC, id DESC` 커서(첫 페이지/커서 페이지 쿼리 분리 —
  Instant null 파라미터의 타입 추론 문제를 피한다).
- `GET /v1/collections/{collectionId}` — 소유자 상세. 내부 Record는
  **`collection_record.created_at DESC`(담은 순서 최신순)** + 커서, `recordSize` 기본 1(명세 7.3).
  `addedToCollectionAt` 포함, 소유자는 `contexts` 포함. 링크→Record→Place→Context를 각각 일괄
  조회해 N+1 없이 조립한다.
- `PATCH /v1/collections/{collectionId}` — 제목 수정(소유자만).
- `POST /v1/collections/{collectionId}/records` — **멱등 추가**(명세 7.5): 이미 담긴 Record는
  건너뛰고 나머지만 담는다. `findByIdForUpdate`(PESSIMISTIC_WRITE)로 Collection을 잠근 뒤 활성 연결
  판단과 `record_count` 갱신을 같은 트랜잭션에서 수행(데이터모델 6.7과 같은 경합 구조).
- 타인 조회·수정은 전부 404 은닉. 타인용 공개 응답은 S15P11A705-71에서 붙는다.

## 티켓과 정본 스펙의 충돌 (Jira 댓글로 기록)

1. 내부 정렬 — 티켓 "Record createdAt 오름차순" vs 정본 `collection_record.created_at DESC`
   (명세 7.3·14-8, 데이터모델 2.7). **정본 채택.**
2. 중복 Record 추가 — 티켓 "거절" vs 정본 "중복만 건너뛰고 멱등"(명세 7.5). **정본 채택.**
   중복 행이 생기지 않는 것은 동일하게 보장(사전 필터 + 부분 유니크).

## 검증

- `CollectionApiTests` 10건 — 생성 201·자동 발행·record_count / 0개·빈 제목·21자 400 / 남의 Record
  404 / 목록 커서(최신순·다음 페이지) / 상세 담은순 DESC 커서·addedToCollectionAt·contexts / 타인
  상세 404 / 제목 수정·타인 404 / 멱등 추가(연결 행·record_count 검증) / 타인 추가 404.
- `./gradlew clean check --no-daemon` 통과.
