# BI-08. core 스키마 V3 마이그레이션과 도메인 엔티티·리포지토리 기반 구축

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-66, [BD-25](../decisions/BD-25-context-origin-created-at.md), [BD-08](../decisions/BD-08-soft-delete-no-restore.md), [BD-14](../decisions/BD-14-identifier-concealment.md)

## 산출

- `src/main/resources/db/migration/V3__core_domain.sql` — `place`·`record`·`context`·`collection`·
  `collection_record`·`follow` 6개 테이블. 컬럼은 데이터모델 문서 2.3~2.8, 부분 유니크는 3.1
  (`uq_place_kakao`는 전체 유니크, 나머지는 `WHERE deleted_at IS NULL`), CHECK는 3.2
  (`ck_follow_self`·`ck_context_body`·`ck_collection_title`·`ck_collection_count`·`ck_place_lat`·
  `ck_place_lng`), 조회 인덱스는 3.3을 따랐다. `social_account` 관련 제약·인덱스는 인증 PR 몫이라
  제외했다.
- `domain/place/entity/Place` — `deleted_at`이 없어 `BaseEntity`를 상속하지 않는 유일한 엔티티.
  감사 리스너가 상속되지 않으므로 `@EntityListeners(AuditingEntityListener.class)`를 직접 붙였다.
- `domain/record/entity/Record`·`Context`, `domain/collection/entity/Collection`·`CollectionRecord`,
  `domain/follow/entity/Follow` — `BaseEntity` 상속 + 테이블별 `@SQLDelete`/`@SQLRestriction`.
  `updated_at`이 있는 `Record`·`Collection`은 `@LastModifiedDate` 필드를 직접 선언했다.
- 연관관계 대신 **Long FK 컬럼 매핑**을 채택했다. 서비스가 사용자 식별자를 `memberId` 파라미터로
  받는 구조(BD-14)와 정합하고, 이후 티켓(70)의 행 잠금·활성 수 카운트 쿼리가 조인 없이 단순해진다.
- `Context.create()`는 `@PrePersist`에서 `origin_created_at`을 자신의 `created_at`과 같은 값으로
  채운다 — JPA 콜백 순서상 상위 클래스에 선언된 `AuditingEntityListener`가 엔티티 자신의
  `@PrePersist`보다 먼저 실행되어 `created_at`이 이미 채워져 있다. 교체 생성(`Context.replacing`)은
  구 Context의 값을 승계한다(BD-25). `Collection`도 같은 방식으로 `published_at`을 생성 시각과 같은
  값으로 채운다(BD-23 생성 즉시 자동 발행).
- 인증 스텁·컨트롤러·서비스는 내지 않았다(티켓 명시). back#28에서 합의한 `@LoginMember` 스텁 계약은
  첫 컨트롤러가 나오는 S15P11A705-67에서 도입한다.

## 검증

- `FlywayMigrationTests` — 6개 테이블·부분 유니크(전체 유니크와 구분)·CHECK·조회 인덱스·
  `origin_created_at NOT NULL` 존재를 빈 DB 전체 마이그레이션으로 검증.
- `RecordPersistenceTests` — 감사 필드, 동일 member+place 활성 Record 중복 거절, 소프트 삭제 후
  재저장 허용(부분 유니크의 존재 이유).
- `ContextPersistenceTests` — 첫 생성 `origin_created_at == created_at`, 교체 생성 승계, 공백 본문
  CHECK 거절.
- `PlacePersistenceTests`·`CollectionPersistenceTests`·`FollowPersistenceTests` — 감사 필드,
  자기 팔로우 CHECK 거절, 중복 연결·중복 팔로우 부분 유니크 거절.
- `./gradlew clean check --no-daemon` 통과.
