# BI-02. Member 엔티티 + BaseEntity(soft delete) 구현

- **상태**: ✅ 완료
- **날짜**: 2026-07-27
- **관련**: S15P11A705-41 (`e755b1e`·`0f3c4d6`·`d1204bf`·`02fcaa7`·`6c61272`·`1acd804`·`67bcd11`), [BD-02](../decisions/BD-02-base-entity-common-columns.md)

## 산출

- `src/main/resources/db/migration/V2__member.sql` — 백엔드 소유 첫 도메인 마이그레이션. `core.member(id BIGINT GENERATED ALWAYS AS IDENTITY PK, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ)`. 활성 회원 조회를 위한 부분 인덱스 `ix_member_active ON core.member(id) WHERE deleted_at IS NULL`. `status` 컬럼은 두지 않는다 — 삭제 판정 기준은 `deleted_at` 하나다.
- `global/common/BaseEntity` — `@CreatedDate`로 채워지는 `created_at`(`Instant`, `NOT NULL`, `updatable=false`)과 `deleted_at`, `isDeleted()`/`softDelete()` 헬퍼를 제공하는 `@MappedSuperclass`. `updated_at`은 두지 않는다([BD-02](../decisions/BD-02-base-entity-common-columns.md) 참조).
- `global/config/JpaAuditingConfig` — `@EnableJpaAuditing`으로 Spring Data JPA 감사 기능을 활성화.
- `domain/member/entity/Member` — `@Table(name = "member", schema = "core")`, `BaseEntity` 상속, `@SQLDelete(sql = "UPDATE core.member SET deleted_at = now() WHERE id = ?")` + `@SQLRestriction("deleted_at IS NULL")`로 soft delete 실동작을 구현. 정적 팩토리 `Member.create()`만 노출(개인정보 컬럼 없음).
- `domain/member/repository/MemberRepository` — `JpaRepository<Member, Long>`.
- `src/main/resources/application-local.yml` — compose가 띄운 로컬 Postgres/Redis 접속 override.
- `docs/development/database-conventions.md` — "공통 컬럼과 BaseEntity" 절 신설(8개 테이블 컬럼 매트릭스, BaseEntity 범위, soft delete·restore 미제공 사유).

## 검증

세 테스트 클래스로 검증했다(전부 `PostgresContainerSupport` 상속, PostgreSQL Testcontainers 기반).

- **`FlywayMigrationTests#memberTableIsCreatedByBackendMigration`**: `core.member`의 JDBC 메타데이터 컬럼 집합이 `{id, created_at, deleted_at}` 정확히 3개임을 확인(`status`·`updated_at` 없음).
- **`MemberPersistenceTests`**: `memberRepository.save(Member.create())` 후 `id` 발급, `createdAt` 자동 기록(`@EnableJpaAuditing` 동작 확인), `isDeleted()==false`, `findById`로 조회됨을 확인.
- **`MemberSoftDeleteTests`**: `repository.delete()` 호출 후 raw SQL로 `core.member`에 물리 행이 여전히 1건 존재하면서 `deleted_at IS NOT NULL`임을 확인(물리 삭제가 아님). `findById`/`findAll` 경로에서는 삭제분이 제외됨을 확인. `softDelete()` 헬퍼 호출 후 `saveAndFlush`로도 동일하게 `deleted_at`이 기록되고 조회에서 제외됨을 확인.

실행: `./gradlew clean check --no-daemon` → **BUILD SUCCESSFUL**, 14/14 테스트 통과(연속 2회 실행하여 재현성 확인, [BT-01](../troubleshooting/BT-01-shared-testcontainers-lifecycle.md)의 컨테이너 생애주기 수정 이후).

## 주의점

`@EnableJpaAuditing`(`JpaAuditingConfig`)이 없으면 `BaseEntity`의 `@CreatedDate` 필드가 채워지지 않아 `created_at`이 `null`로 INSERT되고, `core.member.created_at NOT NULL` 제약 위반으로 저장이 실패한다. `BaseEntity`를 상속하는 다음 도메인 엔티티도 같은 함정을 반복할 수 있으므로, 새 도메인을 추가할 때 `JpaAuditingConfig`가 로드되는 컨텍스트인지(컴포넌트 스캔 범위) 먼저 확인한다.

## 범위 밖

`service`·`controller`·DTO는 만들지 않았다. 다음 PR에서 다룬다. 인증 수단(`social_account`)도 이번 범위가 아니며 인증 PR에서 추가한다.
