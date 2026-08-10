# BD-02. BaseEntity는 created_at·deleted_at만 공유하고 updated_at은 두지 않는다

- **상태**: Accepted
- **날짜**: 2026-07-27
- **관련**: Jira 작업 (`0f3c4d6`), [BI-02](../implements/BI-02-2026-07-27-member-base-entity-soft-delete.md)

## 맥락

`member`를 시작으로 `core` 도메인 엔티티를 만들면서, 여러 테이블이 공유하는 시간·삭제 컬럼을 매번 반복 선언할지, 공통 상위 클래스(`BaseEntity`)로 뽑을지 정해야 했다. 애플리케이션의 Hibernate 설정은 `ddl-auto=validate`다 — 엔티티가 매핑하는 컬럼이 실제 테이블에 없으면 스키마 검증에 실패해 **애플리케이션이 기동조차 되지 않는다.**

`docs/static/06_데이터모델_및_무결성.md` 2장 기준 `core` 8개 테이블의 컬럼 매트릭스는 다음과 같다.

| 테이블 | `created_at` | `updated_at` | `deleted_at` |
|---|---|---|---|
| `member` | O | X | O |
| `social_account` | O | X | O |
| `place` | O | O | X |
| `record` | O | O | O |
| `context` | O | X (불변) | O |
| `collection` | O | O | O |
| `collection_record` | O | X | O |
| `follow` | O | X | O |

집계하면 `created_at` 8/8, `deleted_at` 7/8(`place` 제외), `updated_at` 3/8(`place`·`record`·`collection`만)이다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 단일 `BaseEntity`(created+updated+deleted 전부 포함) | 참고한 ujax 레포 방식과 동일, 구현이 가장 단순 | `updated_at`이 없는 5개 테이블(`member`·`social_account`·`context`·`collection_record`·`follow`)이 상속하면 매핑 컬럼이 실제 테이블에 없어 `ddl-auto=validate` 기동 검증에서 **실패한다.** 이 스키마에서는 채택 불가 |
| (b) 4계층 완전 조합(`BaseCreated`/`SoftDeleteCreated`/`BaseTime`/`SoftDeleteTime`) | 상속 조합만으로 8개 테이블의 모든 컬럼 유무 케이스를 커버 | soft delete 헬퍼(`isDeleted()`/`softDelete()`)가 `SoftDeleteCreated`와 `SoftDeleteTime` 두 곳에 중복되고, 유지할 클래스가 4개로 늘어나 지금 필요 이상으로 무겁다 |
| (c) `created_at`+`deleted_at` 공유, `updated_at`은 엔티티별 선언 | 8개 중 7개가 공유하는 두 컬럼만 상위 클래스로 뽑아 중복을 줄이면서 검증 실패 위험이 없다. `place`는 애초에 상속하지 않는다 | `updated_at`이 필요한 3개 테이블(`place`·`record`·`collection`)은 `@LastModifiedDate` 필드를 각자 선언해야 함 |

## 결정

**(c)를 채택한다.** `BaseEntity`는 `created_at`과 `deleted_at`만 갖는다. `updated_at`은 두지 않고, 필요한 엔티티가 직접 `@LastModifiedDate` 필드를 선언한다. `deleted_at`이 없는 `place`는 `BaseEntity`를 상속하지 않는다. `created_at`만 가진 상위 클래스(예: `place` 전용)는 실제로 필요해질 때 분리한다(YAGNI) — 지금은 `place` 하나뿐이라 분리 이득이 없다.

`BaseEntity`를 상속하지 않으면 그 위에 붙은 `@EntityListeners(AuditingEntityListener.class)`도 함께 상속되지 않는다는 점에 주의해야 한다. `place`처럼 `BaseEntity`를 상속하지 않고 `@CreatedDate`/`@LastModifiedDate`를 쓰려면, 감사 리스너가 상속되지 않으므로 `@EntityListeners(AuditingEntityListener.class)`를 엔티티에 직접 선언해야 한다. 그렇지 않으면 `created_at`이 `null`인 채로 INSERT되어(DB `DEFAULT now()`는 Hibernate가 컬럼에 명시적으로 `NULL`을 쓰기 때문에 구제되지 않는다) `NOT NULL` 제약을 위반한다.

soft delete의 실동작(`@SQLDelete`/`@SQLRestriction`)은 SQL에 테이블명이 필요해 상속으로 공유할 수 없으므로 엔티티마다 명시한다. `restore()`는 제공하지 않는다 — `@SQLRestriction`이 걸리면 삭제된 행을 애초에 로드할 수 없어 복원 메서드가 있어도 동작하지 않는다. 복원이 필요해지면 native 쿼리나 Hibernate filter opt-out(AOP)을 함께 도입한다.

## 결과

- 이 결정으로 감수하는 것: `updated_at`이 필요한 엔티티마다 `@LastModifiedDate` 필드를 반복 선언해야 한다. soft delete SQL도 상속으로 공유되지 않아 엔티티마다 한 줄씩 반복된다.
- 규약으로 승격: [`docs/development/database-conventions.md`](../../development/database-conventions.md) "공통 컬럼과 BaseEntity" 절.
- 재검토 트리거: `updated_at` 없이 시작한 테이블에 나중에 `updated_at`이 추가되거나, `created_at`만 공유하는 엔티티가 2개 이상으로 늘어나 별도 상위 클래스 분리 이득이 생길 때. 삭제 복원(restore) 요구가 실제로 생길 때.
