# T9. H2가 pgvector를 지원하지 않아 마이그레이션 검증을 Testcontainers로 전환했다

- **상태**: 해결됨 (back#12, `564f3e4`)
- **날짜**: 2026-07-23
- **레이어**: 빌드·테스트 런타임 / DB

## 증상

Flyway 마이그레이션 검증을 H2로 시도했으나 `CREATE EXTENSION vector`·`VECTOR(1536)` 타입에서 실패했다.

## 원인

H2는 pgvector 확장을 지원하지 않는다. `ai` 스키마는 pgvector에 의존하고(`VECTOR(1536)`), `V1`은 `CREATE EXTENSION vector`를 실행하므로, PostgreSQL 전용 DDL을 H2로 대체 검증할 수 없다.

## 해결

- `build.gradle`에서 H2 의존성(`spring-boot-h2console`·`com.h2database:h2`)을 제거했다. 백엔드가 back#12에서 수행했다.
- **Testcontainers PostgreSQL**(`pgvector/pgvector:0.8.1-pg16`)로 마이그레이션 검증을 구현했다(`FlywayMigrationTests`·`PostgresContainerSupport`).
- 재발 방지를 규약으로 승격해 [`docs/development/database-conventions.md`](../../development/database-conventions.md)에 "H2 금지, migration PR은 PostgreSQL Testcontainers 검증 추가"로 남겼다.

## 보존 사유

문제 자체는 해결됐고 H2도 프로젝트에서 제거됐다. 그럼에도 이 문서를 유지하는 이유는 **문제 해결 과정**을 회고·복기에서 추적하기 위해서다. H2로 검증을 시도했고, pgvector 미지원을 발견했고, H2를 제거한 뒤 Testcontainers로 전환했다는 흐름이 그것이다. 현재의 규약이라는 결론은 `database-conventions.md`가 담고, 그 규약에 이르게 된 과정이라는 경험은 이 문서가 담는다.
