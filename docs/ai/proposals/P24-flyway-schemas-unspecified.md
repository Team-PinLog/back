# P24: `flyway.schemas` 미지정 — 이력 테이블을 public에

- **상태**: Accepted
- **날짜**: 2026-07-23
- **관련 PR/커밋**: [back#3](https://github.com/Team-PinLog/back/pull/3) (`946df11`)
- **주도(Driver)**: 백엔드

## 맥락

스키마 생성은 `V1`(`CREATE SCHEMA core; CREATE SCHEMA ai; CREATE EXTENSION vector;`)이 전담한다. Flyway에는 `spring.flyway.schemas` 설정이 있는데, 이걸 `core`나 `ai`로 지정하면 두 가지 문제가 생긴다.

1. Flyway가 지정된 첫 스키마를 **자동 생성**하려 시도해 `V1`의 `CREATE SCHEMA`와 역할이 충돌한다(스키마 생성 주체가 둘로 갈림).
2. `flyway_schema_history` 이력 테이블이 그 스키마(`core`) 안에 생겨, 도메인 테이블과 인프라성 이력 테이블이 뒤섞인다.

## 결정

- `spring.flyway.schemas`를 **지정하지 않는다.** 미지정 시 `flyway_schema_history`는 `public`에 생성돼 도메인 스키마(`core`/`ai`)와 분리된다.
- 스키마 생성은 `V1`이 단독으로 담당한다.
- `spring.jpa.hibernate.ddl-auto: validate` — 스키마 원본은 마이그레이션이고, JPA는 검증만 한다.

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    # schemas 미지정: 스키마 생성은 V1이 전담, 이력은 public.
  jpa:
    hibernate:
      ddl-auto: validate
```

## 근거

- **스키마 생성 주체를 하나로 고정**한다. `V1`이 유일한 생성 지점이라, `V1`이 스키마를 `IF NOT EXISTS` 없이 만들어 백엔드가 `V2`에서 재선언하면 명시적으로 실패하게 해 경계를 보호한다.
- **이력과 도메인을 분리**한다. `public.flyway_schema_history`는 인프라성 메타데이터이고 `core`/`ai`는 도메인이라, 백업·권한·조회 관점에서 섞이지 않는 편이 깔끔하다.
- **`validate`**는 마이그레이션을 스키마의 단일 원본으로 두겠다는 [P21](P21-flyway-migration-convention.md)의 연장이다. Hibernate `update`/`create`가 스키마를 몰래 바꾸는 경로를 막는다.

## 버린 대안

- **`schemas: core` 지정**: 이력이 `core`에 들어가고 Flyway가 스키마 자동 생성을 시도해 `V1`과 충돌.
- **`ddl-auto: update`**: JPA 엔티티가 진실의 원본이 되어 마이그레이션과 이중 관리. 파트 경계·검증 가능성이 무너진다.

## 영향

- 마이그레이션·벡터 검증은 pgvector를 지원하는 PostgreSQL에서만 가능하다(H2 불가) → [troubleshooting/h2-pgvector-incompat.md](../troubleshooting/h2-pgvector-incompat.md).
- 엔티티(JPA) 매핑은 마이그레이션이 만든 스키마와 정확히 일치해야 `validate`를 통과한다.

## 검증

- pgvector 컨테이너 기동 후 앱 시작 시 Flyway가 `V1~V102`를 적용하고 `public.flyway_schema_history`에 이력이 쌓임을 확인.
- `psql`에서 `\dn`으로 `core`·`ai` 존재, `\dt public.*`로 이력 테이블 위치 확인.
