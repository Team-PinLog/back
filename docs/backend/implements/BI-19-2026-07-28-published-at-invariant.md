# BI-19. Collection `published_at` DB 불변식

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-125, [back#58](https://github.com/Team-PinLog/back/issues/58),
  [BD-33](../decisions/BD-33-published-at-database-invariant.md)

## 산출

- `V5__collection_published_at_not_null.sql`에서 기존 NULL을 `created_at`으로 백필한다.
- 같은 마이그레이션에서 `published_at DEFAULT now()`와 `NOT NULL`을 적용한다.
- `Collection.publishedAt` JPA 매핑에도 `nullable = false`를 선언한다.
- 기존 `(is_published, published_at DESC)` 부분 인덱스는 변경하지 않는다.

## 검증

`PublishedAtMigrationTests`는 V3까지만 적용한 별도 PostgreSQL DB에 NULL 행을 만든 후 최신
버전으로 마이그레이션하여 다음을 검증한다.

- 기존 NULL 행의 `published_at`이 해당 행의 `created_at`과 같다.
- `information_schema`에서 컬럼이 `NOT NULL`이고 기본값이 `now()`다.
- `published_at`을 생략한 native INSERT도 NULL이 아닌 값을 얻는다.

기존 JPA 생성 경로는 `@PrePersist`가 `created_at`과 같은 발행 시각을 채우며, 마이그레이션 테스트와
전체 `clean check`로 함께 검증한다.
