# 데이터베이스 개발 규약

시작 절차와 PR 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 PostgreSQL schema, Flyway migration과 DB 테스트의 상세 기준입니다.

## 지원 데이터베이스

PostgreSQL만 지원합니다. 로컬 실행, 통합 테스트와 CI는 `pgvector/pgvector:0.8.1-pg16` PostgreSQL을 기준으로 합니다. H2를 추가하거나 PostgreSQL 전용 migration의 대체 검증으로 사용하지 않습니다.

애플리케이션의 Hibernate 설정은 `ddl-auto=validate`입니다. schema 변경은 Hibernate 자동 생성이 아니라 Flyway migration으로 관리합니다.

## Flyway 버전과 소유 경계

| 버전 | 소유 | 용도 |
| --- | --- | --- |
| `V1` | 공통 기반 | `core`·`ai` schema와 `vector` extension의 초기 기반 |
| `V2`~`V99` | 백엔드 | `core` 도메인 테이블과 제약 |
| `V100`~`V199` | AI | AI schema, AI 소유 인덱스와 `core.feed_event` |

자기 소유 구간만 사용합니다. `core` schema에 있어도 `core.feed_event`는 AI가 소유한 V102 migration이므로 재정의하지 않습니다. 표 밖의 버전이 필요하면 파일을 만들기 전에 관련 소유자와 번호 및 책임을 합의합니다.

이미 적용된 migration은 절대로 수정하지 않습니다. 변경이 필요하면 다음 새 버전 migration을 추가합니다. 현재 파일과 적용 순서의 구현 세부는 [db/migration README](../../src/main/resources/db/migration/README.md)에서 확인합니다.

## migration과 DB 변경 검증

- migration을 추가하거나 변경하는 PR은 빈 PostgreSQL DB에서 전체 migration이 적용되는 Testcontainers 검증을 추가하거나 갱신합니다.
- Repository, Flyway와 PostgreSQL 기능을 쓰는 테스트는 PostgreSQL Testcontainers를 사용합니다.
- DB 변경 PR은 `./gradlew clean check --no-daemon`을 실행해 PostgreSQL 통합 테스트를 통과해야 합니다.
- Docker가 실행되지 않으면 DB 테스트를 skip하지 않습니다. 원인을 표시해 실패하게 하고 Docker를 시작한 뒤 다시 실행합니다.

## 공통 컬럼과 BaseEntity

`core` 테이블의 공통 컬럼은 세 가지이며, 테이블마다 조합이 다릅니다(근거: `docs/static/06_데이터모델_및_무결성.md` 2장).

| 테이블 | `created_at` | `updated_at` | `deleted_at` |
| --- | --- | --- | --- |
| `member` | O | X | O |
| `social_account` | O | X | O |
| `place` | O | O | X |
| `record` | O | O | O |
| `context` | O | X (불변) | O |
| `collection` | O | O | O |
| `collection_record` | O | X | O |
| `follow` | O | X | O |

- `global/common/BaseEntity`는 **`created_at`과 `deleted_at`만** 제공합니다. `created_at`은 모든 테이블에 있고, `deleted_at`은 `place`를 제외한 전부에 있습니다.
- **`updated_at`은 BaseEntity에 두지 않습니다.** 8개 중 3개(`place`·`record`·`collection`)에만 있으므로, 필요한 엔티티가 `@LastModifiedDate` 필드를 직접 선언합니다. 없는 테이블에 상속시키면 `ddl-auto=validate`가 기동 시 실패합니다.
- `deleted_at`이 없는 테이블(`place`)은 `BaseEntity`를 상속하지 않고 `created_at`·`updated_at`을 직접 선언합니다. `created_at`만 공유하는 상위 클래스가 필요해지면 그때 분리합니다.
  - **주의**: `@EntityListeners(AuditingEntityListener.class)`는 `BaseEntity`에 선언되어 있고 **상속되지 않습니다.** `BaseEntity`를 상속하지 않는 엔티티(`place`)는 `@CreatedDate`(및 `@LastModifiedDate`)를 쓰려면 이 어노테이션을 엔티티에 직접 붙여야 합니다. 붙이지 않으면 감사 리스너가 동작하지 않아 `created_at`이 채워지지 않은 채(`null`) INSERT되고, DB의 `DEFAULT now()`는 Hibernate가 매핑 컬럼에 명시적으로 `NULL`을 쓰기 때문에 구제해주지 않아 `NOT NULL` 제약 위반으로 저장이 실패합니다.
- soft delete의 실동작은 **엔티티마다** 명시합니다. 테이블명이 필요해 상속으로 공유할 수 없습니다.
- **`@SQLDelete`는 엔티티 단위 삭제(`repository.delete(entity)` 등)에만 적용됩니다.** `deleteAllInBatch()`, `deleteAllByIdInBatch()`, bulk JPQL `delete` 쿼리는 `@SQLDelete`를 거치지 않고 `@SQLRestriction`도 덧붙지 않는 **물리 삭제**입니다. soft delete가 계약인 테이블(`member` 등)에서는 이 벌크 삭제 메서드들을 사용하지 않습니다 — 삭제되지 않은 행까지 포함해 물리적으로 지워질 수 있습니다.
- **soft delete 쓰기 경로의 기준 시각이 둘로 갈립니다.** `Member`의 `@SQLDelete`는 DB 시각(`now()`)을, `BaseEntity.softDelete()`는 애플리케이션 시각(`Instant.now()`)을 씁니다. 또한 `repository.delete(entity)` 호출 후에도 메모리상의 `entity` 인스턴스는 다시 조회하기 전까지 `isDeleted() == false`를 반환합니다 — `@SQLDelete`는 DB 행만 갱신할 뿐 영속성 컨텍스트의 필드는 갱신하지 않기 때문입니다.

```java
@SQLDelete(sql = "UPDATE core.member SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
```

- `@SQLRestriction`이 걸린 엔티티는 삭제된 행을 조회할 수 없으므로 **복원 기능을 제공하지 않습니다.** 복원이 필요해지면 native 쿼리 또는 Hibernate filter opt-out을 함께 도입합니다.
- 엔티티는 `@Table(schema = "core")`로 스키마를 명시합니다. 전역 `default_schema` 설정을 쓰지 않습니다.
- Java 시간 타입은 `Instant`를 사용합니다(`TIMESTAMPTZ` ↔ UTC, ISO-8601 UTC 계약과 정합).
