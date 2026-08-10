# 데이터베이스 개발 규약

시작 절차와 PR 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 PostgreSQL schema, Flyway migration과 DB 테스트의 상세 기준입니다.

## 지원 데이터베이스

PostgreSQL만 지원합니다. 로컬 실행, 통합 테스트와 CI는 `pgvector/pgvector:0.8.5-pg16` PostgreSQL을 기준으로 합니다. 운영 이미지(Jira 작업)와 같은 버전으로 맞춥니다. H2를 추가하거나 PostgreSQL 전용 migration의 대체 검증으로 사용하지 않습니다.

`compose.yaml`은 digest까지 고정하고, Testcontainers(`PostgresContainerSupport`)는 같은 태그를 씁니다.

> **이미지를 올려도 기존 volume의 extension은 따라 올라가지 않습니다.** `CREATE EXTENSION IF NOT EXISTS vector`는 이미 설치된 extension을 업그레이드하지 않으므로, 기존 `postgres-data`를 재사용하면 바이너리만 새 버전이고 `pg_extension.extversion`은 옛 버전으로 남습니다. 확인과 조치는 아래와 같습니다.
>
> ```sql
> SELECT name, default_version, installed_version FROM pg_available_extensions WHERE name = 'vector';
> ALTER EXTENSION vector UPDATE;
> ```

애플리케이션의 Hibernate 설정은 `ddl-auto=validate`입니다. schema 변경은 Hibernate 자동 생성이 아니라 Flyway migration으로 관리합니다.

> 결정 배경: [BD-01](../backend/decisions/BD-01-h2-removal-testcontainers.md) H2 제거와 Testcontainers 단일화 · [P24](../ai/proposals/P24-flyway-schemas-unspecified.md) `flyway.schemas` 미지정과 `ddl-auto=validate`

## Flyway 버전과 소유 경계

| 버전 | 소유 | 용도 |
| --- | --- | --- |
| `V1` | 공통 기반 | `core`·`ai` schema와 `vector` extension의 초기 기반 |
| `V2`~`V99` | 백엔드 | `core` 도메인 테이블과 제약 |
| `V100`~`V199` | AI | AI schema, AI 소유 인덱스와 `core.feed_event` |

자기 소유 구간만 사용합니다. `core` schema에 있어도 `core.feed_event`는 AI가 소유한 V102 migration이므로 재정의하지 않습니다. 표 밖의 버전이 필요하면 파일을 만들기 전에 관련 소유자와 번호 및 책임을 합의합니다.

이미 적용된 migration은 절대로 수정하지 않습니다. 변경이 필요하면 다음 새 버전 migration을 추가합니다. 현재 파일과 적용 순서의 구현 세부는 [db/migration README](../../src/main/resources/db/migration/README.md)에서 확인합니다.

### out-of-order를 허용합니다

AI 구간(`V100`~)이 백엔드 구간(`V2`~) **위**에 있으므로, AI migration이 이미 적용된 DB에서는 백엔드가 추가하는 번호가 항상 적용된 최대 버전보다 낮습니다. Flyway 기본값은 이를 거부하고 **기동을 실패시킵니다.** 그래서 `spring.flyway.out-of-order: true`를 적용했습니다.

- 이 설정을 끄면 백엔드 migration을 추가하는 순간 기존 DB에서 기동이 깨집니다. 끄기 전에 [BD-26](../backend/decisions/BD-26-flyway-out-of-order.md)의 재검토 트리거를 확인합니다.
- **적용 순서가 환경마다 다릅니다.** 빈 DB는 `V1 → V2 → V100`, 기존 DB는 `V1 → V100 → V102 → V2`입니다. 따라서 **migration은 다른 구간의 객체에 의존하지 않아야 합니다.** `core`와 `ai`가 스키마로 분리되어 서로를 정의하지 않는다는 전제가 이 설정을 안전하게 만듭니다.
- `core.feed_event`(AI 소유 `V102`)에 의존하는 제약·인덱스를 백엔드가 추가하려 하면 그 전제가 깨집니다. 추가하기 전에 BD-26을 재검토합니다.
- out-of-order는 "빠진 낮은 번호"를 정상으로 취급하므로, 이력이 어긋난 상황을 Flyway가 더 이상 알려주지 않습니다.

> 결정 배경: [BD-26](../backend/decisions/BD-26-flyway-out-of-order.md) 구간 소유 유지와 out-of-order 허용 · [BT-02](../backend/troubleshooting/BT-02-flyway-out-of-order-version-ranges.md) 증상과 진단

## migration과 DB 변경 검증

- migration을 추가하거나 변경하는 PR은 빈 PostgreSQL DB에서 전체 migration이 적용되는 Testcontainers 검증을 추가하거나 갱신합니다.
- Repository, Flyway와 PostgreSQL 기능을 쓰는 테스트는 PostgreSQL Testcontainers를 사용합니다.
- DB 변경 PR은 `./gradlew clean check --no-daemon`을 실행해 PostgreSQL 통합 테스트를 통과해야 합니다.
- Docker가 실행되지 않으면 DB 테스트를 skip하지 않습니다. 원인을 표시해 실패하게 하고 Docker를 시작한 뒤 다시 실행합니다.

## 무중단 배포와 backward-compatible migration

RollingUpdate 중에는 **구 버전 Pod와 신 버전 Pod가 같은 DB를 동시에 봅니다.** migration은 신 Pod가 뜨는 시점에 적용되지만 구 Pod는 아직 살아 있으므로, 구 버전 코드가 계속 동작하는 형태로만 schema를 바꿉니다.

한 번의 migration에서 하지 않습니다.

- 컬럼·테이블 즉시 `DROP` — 구 Pod의 `SELECT`가 깨집니다
- 컬럼 rename — `DROP` + `ADD`와 같습니다
- 기존 컬럼에 `NOT NULL` 즉시 추가 — 해당 컬럼을 채우지 않는 구 Pod의 `INSERT`가 실패합니다
- 타입 축소 변경(길이 축소, 범위가 좁은 타입으로 변경)

제거가 필요하면 릴리스를 나눕니다.

1. 추가 — 새 컬럼을 nullable로 추가하고 백필한다. 필요하면 신·구 컬럼에 함께 쓴다
2. 배포 — 구 Pod가 모두 교체될 때까지 기다린다
3. 제거 — **다음 릴리스의 새 migration**에서 구 컬럼을 지우고 `NOT NULL`을 건다

CI의 `FlywayMigrationTests`는 **빈 DB에 전체 migration을 적용하는 것까지만** 검증합니다. 구 Pod 호환성은 자동으로 잡히지 않으므로 이 규약과 리뷰로 지킵니다. 근거: `Jira 작업`.

기존 DB에 migration을 추가하는 경로는 `FlywayOutOfOrderTests`가 별도로 검증합니다 — AI 구간만 적용된 DB를 재현해 백엔드 migration이 적용되는지 확인합니다. 두 테스트의 역할이 다르므로 **둘 다 유지합니다**: 하나는 최초 배포, 하나는 그 이후를 지킵니다.

## 공통 컬럼과 BaseEntity

`core` 테이블의 공통 컬럼은 세 가지이며, 테이블마다 조합이 다릅니다(근거: `docs/static/06_데이터모델_및_무결성.md` 2장).

> 결정 배경: [BD-02](../backend/decisions/BD-02-base-entity-common-columns.md) BaseEntity가 `created_at`·`deleted_at`만 공유하는 이유 · [BD-08](../backend/decisions/BD-08-soft-delete-no-restore.md) 소프트 삭제·복구 없음과 활성행 부분 유니크 · [BD-10](../backend/decisions/BD-10-integrity-in-database.md) 무결성을 DB 제약에 둔 이유 · [BD-07](../backend/decisions/BD-07-context-immutability.md) `context`에 `updated_at`이 없는 이유

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
```java
@SQLDelete(sql = "UPDATE core.member SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
```

### 삭제하는 방법은 `softDelete()`입니다 — `@SQLDelete`는 안전망입니다

soft delete를 일으키는 경로가 둘 있습니다. **역할이 다르므로 골라 쓰는 것이 아닙니다.**

| | 역할 | 언제 |
| --- | --- | --- |
| `BaseEntity.softDelete()` + `save` | **정식 경로.** 도메인 코드가 삭제할 때 쓴다 | 항상 이것을 씁니다 |
| `@SQLDelete` | **안전망.** 실수로 물리 삭제가 나가는 것을 막는다 | 누가 `repository.delete()`나 cascade를 호출했을 때 |

`softDelete()`를 정식 경로로 두는 이유는 **호출 직후 메모리의 엔티티가 이미 삭제된 상태로 보인다**는 것입니다. `repository.delete(entity)`는 DB 행만 갱신하므로 같은 인스턴스가 다시 조회되기 전까지 `isDeleted() == false`를 반환합니다 — 그 인스턴스를 보고 판단하는 코드가 조용히 틀립니다. 두 경로의 이 차이는 `MemberSoftDeleteTests`가 각각 고정하고 있습니다.

`@SQLDelete`를 떼지 않는 이유는 그것이 유일한 안전망이기 때문입니다. 떼면 `repository.delete()`·`deleteById()`·`CascadeType.REMOVE`가 전부 **물리 삭제**로 돌아가고, 복원할 수 없는 테이블에서 행이 사라집니다.

**감수하는 것: 기준 시각이 경로에 따라 갈립니다.** `@SQLDelete`는 DB 시각(`now()`)을, `softDelete()`는 애플리케이션 시각(`Instant.now()`)을 씁니다. 한 행에 두 시각이 섞이는 것은 아니고 행마다 출처가 다를 뿐이며, 정식 경로만 쓰면 항상 애플리케이션 시각입니다. 완전히 통일하려면 `repository.delete()`를 호출할 수 없게 만들어야(리포지토리에서 삭제 메서드를 노출하지 않아야) 하는데, 그건 모든 리포지토리가 `JpaRepository` 편의를 포기하는 큰 변경이라 택하지 않았습니다.

- `@SQLRestriction`이 걸린 엔티티는 삭제된 행을 조회할 수 없으므로 **복원 기능을 제공하지 않습니다.** 복원이 필요해지면 native 쿼리 또는 Hibernate filter opt-out을 함께 도입합니다.
- 엔티티는 `@Table(schema = "core")`로 스키마를 명시합니다. 전역 `default_schema` 설정을 쓰지 않습니다.
- Java 시간 타입은 `Instant`를 사용합니다(`TIMESTAMPTZ` ↔ UTC, ISO-8601 UTC 계약과 정합).
