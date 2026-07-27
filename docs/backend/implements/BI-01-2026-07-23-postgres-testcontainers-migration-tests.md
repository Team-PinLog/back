# BI-01. PostgreSQL Testcontainers 마이그레이션 검증 + H2 제거

- **상태**: ✅ 완료
- **날짜**: 2026-07-23
- **관련**: back#12 (`564f3e4`), [BD-01](../decisions/BD-01-h2-removal-testcontainers.md)

## 산출

- `src/test/java/com/pinlog/pinlogback/integration/PostgresContainerSupport.java` — `pgvector/pgvector:0.8.1-pg16` 컨테이너를 `@ServiceConnection`으로 제공하는 공통 베이스.
- `src/test/java/com/pinlog/pinlogback/integration/FlywayMigrationTests.java` — 빈 PostgreSQL에 V1·V100~V102 전체 적용을 검증.
- `build.gradle`에서 H2 의존성 제거, 기존 컨텍스트 로드 테스트(`PinlogBackApplicationTests`·`DeploymentContractTests`)를 Testcontainers 기반으로 전환.
- 문서 갱신: 루트 `README.md`(Docker Desktop 필요 명시, H2 항목 제거), `db/migration/README.md`(검증 방법을 Testcontainers로).

## 검증

`FlywayMigrationTests`가 확인하는 것:

- Flyway 적용 버전에 `1, 100, 101, 102` 포함
- `core`·`ai` 스키마 생성, `vector` extension 설치
- `flyway_schema_history`가 `public` 스키마에 위치 (P24 결정 준수)
- `ai` 5개 테이블(`keyword_preset`·`context_ai_state`·`context_embedding`·`context_keyword`·`context_keyword_analysis`)과 인덱스, `core.feed_event` 존재

실행: `./gradlew test` (Docker Desktop 실행 필요)
