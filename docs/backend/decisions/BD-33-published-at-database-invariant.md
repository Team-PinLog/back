# BD-33. 발행된 Collection의 `published_at`을 DB `CHECK`로 강제한다

- **상태**: Accepted
- **날짜**: 2026-07-28
- **관련**: S15P11A705-125, S15P11A705-119(Feed 정책·계약),
  [back#58](https://github.com/Team-PinLog/back/issues/58)

## 맥락

Collection은 생성 즉시 자동 발행되며 엔티티 `@PrePersist`가 `published_at = created_at`을 채운다.
하지만 DB 컬럼은 nullable이라 JPA를 우회한 INSERT나 결함이 있는 쓰기 경로가 NULL을 만들 수 있다.
PostgreSQL의 `DESC` 정렬은 NULL을 먼저 두므로 Feed 최신순 후보 상단이 오염된다.
무결성을 앱이 아니라 DB에 두는 [BD-10](BD-10-integrity-in-database.md)의 적용 대상이다.

막아야 하는 것은 **"모든 행에 발행 시각이 있다"가 아니라 "발행된 행에 발행 시각이 있다"**이다.
전자로 적으면 [BD-23](BD-23-collection-auto-publish.md)이 `is_published`를 남긴 전제 —
"비공개는 온다" — 를 DB 스키마가 정면으로 부정한다.

## 선택지

| 안 | 오늘의 보호 | 문제 |
|---|---|---|
| (a) `published_at NOT NULL DEFAULT now()` | 전 행 | `is_published`가 false일 수 있다는 전제와 모순된다. 비공개 생성·발행 취소가 들어오면 제약을 풀어야 한다. `DEFAULT now()`가 결함 있는 쓰기 경로의 실패를 그럴듯한 값으로 덮는다 |
| **(b) `CHECK (NOT is_published OR published_at IS NOT NULL)`** | 전 행 — `is_published`가 전부 `true`이므로 동일하다 | 발행되지 않은 행의 NULL은 잡지 못한다. 다만 그것은 잡을 대상이 아니다 |

## 결정

**(b)를 채택한다.** 기존 NULL은 `created_at`으로 백필한 뒤 제약을 건다.

```sql
ALTER TABLE core.collection
    ADD CONSTRAINT ck_collection_published_at
        CHECK (NOT is_published OR published_at IS NOT NULL);
```

컬럼은 nullable로 남기고 JPA 매핑에도 `nullable = false`를 선언하지 않는다. 같은 주장의 복제본을
두 곳에 두지 않는다.

**쌍조건(`is_published = (published_at IS NOT NULL)`)이 아니라 함의 한 방향이다.** 나중에 발행
취소가 들어와도 과거 발행 시각을 남길 수 있어야 하기 때문이다.

**Feed 보호 범위는 (a)와 같다.** Feed 후보 쿼리 세 개가 전부 `WHERE c.is_published = true`를
걸므로, 이 `CHECK`가 정확히 그 집합에 발행 시각이 있음을 보증한다.

## 결과

- 발행된 Collection은 저장 경로와 관계없이 발행 시각을 가진다. `is_published`와 `published_at`이
  어긋난 행 자체가 만들어지지 않는다.
- `published_at`을 빠뜨린 쓰기 경로는 **실패한다.** 방어 대상인 결함 있는 경로를 DB 기본값으로
  덮지 않는다. `published_at = created_at`이라는 불변식도 애플리케이션 시계 한 곳에서만 결정된다 —
  `DEFAULT now()`는 DB 서버의 트랜잭션 시작 시각이라 Spring Data auditing이 채우는 `created_at`과
  갈라질 수 있었다.
- BD-23이 `is_published`에 걸어둔 전제를 존중한다. 비공개 생성이나 발행 취소가 들어와도 이 제약은
  그대로 유효하며 풀 필요가 없다.
- 현재 배포된 구 코드도 모든 Collection INSERT에서 `@PrePersist`로 값을 채우므로 RollingUpdate 중
  구 Pod와 호환된다.

**재검토 트리거**

- 비공개 전환·발행 취소를 도입할 때 "공개 시점에 `published_at`을 갱신하는가"는 여전히 결정해야
  한다. 갱신하면 컬럼의 의미가 "최초 발행"에서 "마지막 발행"으로 바뀌고, 반복 전환으로 Feed
  `recency`를 리셋하는 경로가 열린다. 이 제약은 어느 쪽을 택하든 유효하므로 제약 자체는 건드리지
  않는다.
