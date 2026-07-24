# T9. H2에서 pgvector 검증 불가 → Testcontainers 전환

- **상태**: 해결됨 (back#12, `564f3e4`)
- **날짜**: 2026-07-23
- **레이어**: 빌드·테스트 런타임 / DB

## 증상

Flyway 마이그레이션 검증을 H2로 시도했으나 `CREATE EXTENSION vector`·`VECTOR(1536)` 타입에서 실패했다.

## 원인

H2는 pgvector 확장을 지원하지 않는다. `ai` 스키마는 pgvector에 의존하고(`VECTOR(1536)`), `V1`은 `CREATE EXTENSION vector`를 실행하므로, PostgreSQL 전용 DDL을 H2로 대체 검증할 수 없다.

## 해결

- `build.gradle`에서 H2 의존성 제거(`spring-boot-h2console`·`com.h2database:h2`) — 백엔드, back#12.
- **Testcontainers PostgreSQL**(`pgvector/pgvector:0.8.1-pg16`)로 마이그레이션 검증 구현(`FlywayMigrationTests`·`PostgresContainerSupport`).
- 재발 방지를 규약으로 승격 → [`docs/development/database-conventions.md`](../../development/database-conventions.md)("H2 금지, migration PR은 PostgreSQL Testcontainers 검증 추가").

## 보존 사유

해결됐으나(H2 자체가 프로젝트에서 제거됨) **문제 해결 과정**(H2 시도 → pgvector 미지원 발견 → 제거 후 Testcontainers 전환)을 회고·복기에서 추적하기 위해 유지한다. 현재의 규약(결론)은 `database-conventions.md`가, 그 규약에 이르게 된 과정(경험)은 이 문서가 담는다.
