# BT-01. 공유 Testcontainers Postgres가 클래스마다 재시작되어 뒤 클래스가 죽은 포트를 물음

- **상태**: 해결됨 (S15P11A705-41, `6c61272`)
- **날짜**: 2026-07-27
- **레이어**: 테스트 런타임 / Testcontainers

## 증상

`./gradlew clean check --no-daemon` 전체 실행에서 `MemberSoftDeleteTests`의 DB 관련 테스트가 커넥션 타임아웃으로 깨졌다(14개 중 3개 실패). 같은 클래스만 단독으로 실행하면 통과했다.

## 원인

`PostgresContainerSupport`가 컨테이너 인스턴스를 static 필드로 제공하는데, 이를 상속하는 6개 테스트 클래스가 각자 `@Container static final PostgreSQLContainer<?> postgres = POSTGRES;` 형태로 **같은 인스턴스를 자기 클래스의 `@Container` 필드로 재선언**하고 있었다.

JUnit5 Testcontainers 확장은 `@Container` 필드를 선언한 **클래스 단위**로 컨테이너 생애주기를 관리한다. 즉 어떤 클래스든 `@Container`로 선언하면, 그 클래스의 `afterAll`에서 컨테이너를 정지시킨다. 여섯 클래스가 같은 인스턴스를 각자 `@Container`로 들고 있었으므로, 한 클래스가 끝날 때마다 공유 컨테이너가 멈췄다가 다음 클래스가 시작할 때 다시 뜨면서 **매핑 포트가 바뀌었다.**

`MemberPersistenceTests`와 `MemberSoftDeleteTests`는 `@SpringBootTest` 설정(프로퍼티 포함)이 완전히 동일했고 `@DirtiesContext`가 없었다. Spring Test는 설정이 같은 테스트 클래스 간에 `ApplicationContext`를 캐시해 재사용하므로, 두 클래스는 같은 컨텍스트를 공유했다. 그런데 그 컨텍스트 안의 `HikariDataSource`는 이미 만들어진 커넥션 풀이라, 컨테이너가 재시작되며 포트가 바뀌어도 **예전의 죽은 포트를 계속 가리켰다.** 뒤에 실행된 클래스가 이 죽은 DataSource로 쿼리를 시도하면서 30초 커넥션 타임아웃으로 실패했다.

나머지 4개 클래스(`FlywayMigrationTests` 등)는 `@DirtiesContext(classMode = AFTER_CLASS)`가 붙어 있어 클래스가 끝날 때마다 컨텍스트를 폐기하고 다음 클래스가 DataSource를 새로 만들었기 때문에 증상이 드러나지 않았다. `member` 두 테스트만 이 어노테이션이 없어 문제가 노출됐다.

## 해결

Testcontainers 공식 문서가 권하는 **싱글톤 컨테이너(singleton container)** 패턴으로 전환했다.

- `PostgresContainerSupport`의 static 초기화 블록에서 `POSTGRES.start()`를 한 번만 호출해 JVM 전체 수명 동안 컨테이너 하나를 유지한다.
- 6개 서브클래스에서 `@Testcontainers`/`@Container` 재선언을 전부 제거했다. `@Container` 어노테이션이 어디에도 없으므로 JUnit5 확장이 컨테이너를 정지시키는 지점 자체가 사라진다.
- 컨테이너 종료는 명시적으로 하지 않는다 — Testcontainers의 Ryuk 리소스 리퍼가 JVM 종료 시 정리한다.
- `@ServiceConnection`은 그대로 유지해 Spring Boot가 DataSource 설정을 자동 구성하도록 둔다.

수정 후 `./gradlew clean check --no-daemon`을 연속 2회 실행해 14/14 테스트가 모두 통과함을 확인했다.

## 왜 `@DirtiesContext` 추가가 아닌가

증상만 사라진 4개 클래스처럼 `MemberSoftDeleteTests`에도 `@DirtiesContext`를 붙이면 당장 테스트는 통과한다. 하지만 그러면 **컨테이너가 계속 재시작된다는 원인은 그대로 남고**, 컨텍스트를 매번 새로 띄우는 비용(느려짐)을 감수하면서 증상만 가리는 셈이다. 다음에 `@DirtiesContext` 없는 클래스가 하나라도 추가되면 같은 문제가 재발한다. 원인인 "컨테이너 재시작" 자체를 없애는 편이 근본적이다.

## 남은 과제(후속)

싱글톤 컨테이너로 바뀌면서 이제 모든 테스트 클래스가 **하나의 DB를 프로세스 수명 동안 공유**한다. `FlywayMigrationTests`가 전제하는 "빈 DB에 전체 migration을 적용한다"는 조건은 더 이상 컨테이너 시작 시점에 보장되지 않고, 그 클래스가 실행 순서상 다른 데이터 삽입 테스트보다 먼저 실행되는가에 암묵적으로 의존한다. 지금은 Gradle의 기본 실행 순서에서 우연히 통과하지만, 다음 중 하나가 필요하다.

- `FlywayMigrationTests`의 "빈 DB" 전제를 실행 순서에 의존하지 않는 형태로 명시적으로 만든다(예: 별도 스키마·별도 컨테이너, 혹은 애초에 컬럼 존재만 확인하고 "빈 DB"를 전제하지 않도록 재작성).
- 또는 각 테스트가 끝난 뒤 삽입한 행을 명시적으로 지우는 정리 코드(또는 `EntityManager#clear()` 후 재조회)를 추가한다.

`member` 테스트들에 `@Transactional`을 붙이는 방법은 **여기서는 쓸 수 없다.** `MemberSoftDeleteTests.softDeleteHelperMarksEntityAsDeleted` 등 soft delete 제외 검증은 `repository.delete()`/`softDelete()` 이후 `findById`가 실제로 빈 결과를 반환하는지를 확인하는데, `@Transactional`을 붙이면 테스트 메서드 전체가 하나의 트랜잭션·영속성 컨텍스트를 공유하게 되어 엔티티가 1차 캐시에 계속 관리(managed) 상태로 남는다. 그러면 `findById(id)`가 SQL을 다시 실행하지 않고 1차 캐시에서 바로 응답해 `@SQLRestriction`이 아예 타지 않으므로, 실제로는 걸러져야 할 삭제된 행이 조회되어 제외 검증 자체가 거짓양성으로 통과(또는 실패)하게 된다. 이 클래스들에는 `@Transactional`을 붙이지 않고, 위의 명시적 정리 또는 `EntityManager#clear()` 같은 대안을 쓴다.

이 정리는 이번 태스크 범위 밖이며 후속 작업으로 남긴다.
