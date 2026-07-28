# BD-33. 자동 발행 Collection의 `published_at`을 DB 불변식으로 강제한다

- **상태**: Accepted
- **날짜**: 2026-07-28
- **관련**: S15P11A705-125, S15P11A705-119(Feed 정책·계약),
  [back#58](https://github.com/Team-PinLog/back/issues/58)

## 맥락

Collection은 생성 즉시 자동 발행되며 엔티티 `@PrePersist`가 `published_at = created_at`을 채운다.
하지만 DB 컬럼은 nullable이라 JPA를 우회한 INSERT나 결함이 있는 쓰기 경로가 NULL을 만들 수 있다.
PostgreSQL의 `DESC` 정렬은 NULL을 먼저 두므로 Feed 최신순 후보 상단이 오염된다.

## 결정

기존 NULL은 `created_at`으로 백필하고 `DEFAULT now()`와 `NOT NULL`을 적용한다. JPA 매핑에도
`nullable = false`를 선언한다.

현재 배포된 구 코드도 모든 Collection INSERT에서 `@PrePersist`로 값을 채우므로 RollingUpdate 중
구 Pod와 호환된다. DB 기본값은 native INSERT와 향후 비-JPA 쓰기 경로의 방어선이다.

## 결과

- 자동 발행 Collection은 저장 경로와 관계없이 발행 시각을 가진다.
- 애플리케이션 생성은 기존처럼 `created_at`과 같은 값을 사용하고, 컬럼을 생략한 DB 직접 INSERT는
  DB 시각을 사용한다.
- 향후 예약 발행처럼 발행 전 NULL이 의미를 갖는 기능을 도입하면 이 제약과 BD-23을 함께 재검토한다.
