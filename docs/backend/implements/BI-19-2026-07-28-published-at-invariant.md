# BI-19. Collection `published_at` DB 불변식

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-125, [back#58](https://github.com/Team-PinLog/back/issues/58),
  [BD-33](../decisions/BD-33-published-at-database-invariant.md)

## 산출

- `V5__collection_published_at_invariant.sql`에서 기존 NULL을 `created_at`으로 백필한다.
- 같은 마이그레이션에서 `ck_collection_published_at`
  `CHECK (NOT is_published OR published_at IS NOT NULL)`을 추가한다.
- 컬럼은 nullable로 남기고 `Collection.publishedAt` JPA 매핑도 `@Column(name = "published_at")`
  그대로 둔다. `ddl-auto: validate`는 nullable 컬럼을 nullable 매핑으로 검증하므로 통과한다.
- 기존 `(is_published, published_at DESC)` 부분 인덱스는 변경하지 않는다.

## 검증

`PublishedAtMigrationTests`는 V3까지만 적용한 별도 PostgreSQL DB에 NULL 행을 만든 후 최신
버전으로 마이그레이션하여 다음을 검증한다.

- 기존 NULL 행의 `published_at`이 해당 행의 `created_at`과 같다. (백필이 빠지면 제약 추가 자체가
  실패하므로 마이그레이션이 통과한 것 자체도 백필의 증거다.)
- `pg_constraint`에 `ck_collection_published_at`이 있고 정의가 함의 형태다.
- 컬럼의 `is_nullable`이 `YES`로 남는다 — 제약은 발행된 행에만 건다.
- `is_published = false` + `published_at IS NULL` INSERT가 **통과한다.**
- `is_published = true` + `published_at IS NULL` INSERT가 제약 이름과 함께 **거부된다.**

뒤의 두 케이스가 제약의 의도(함의 한 방향)를 테스트로 고정한다. 기존 JPA 생성 경로는 `@PrePersist`가
`created_at`과 같은 발행 시각을 채우며, 마이그레이션 테스트와 전체 `clean check`로 함께 검증한다.

## 이력

- 2026-07-28 초안은 `NOT NULL DEFAULT now()`였다. back#75 리뷰에서 그 형태가 `is_published`의
  전제와 모순되고 결함 있는 쓰기 경로의 실패를 기본값으로 덮는다는 지적을 받아 `CHECK` 함의형으로
  전환했다. 근거는 [BD-33](../decisions/BD-33-published-at-database-invariant.md)에 있다.
