# H2는 pgvector·VECTOR 타입을 지원하지 않는다

- **날짜**: 2026-07-23
- **관련**: [back#3](https://github.com/Team-PinLog/back/pull/3), [ADR-003](../decisions/ADR-003-flyway-schemas-unspecified.md)
- **레이어**: 빌드·테스트 런타임 / DB

## 증상

`ai` 스키마 테이블은 `embedding VECTOR(1536)` 컬럼을 갖고, `V1`은 `CREATE EXTENSION vector`를 실행한다. 그런데 `build.gradle`의 테스트 런타임은 `runtimeOnly 'com.h2database:h2'`다. H2에서 마이그레이션을 돌리면 `CREATE EXTENSION vector`와 `VECTOR` 타입에서 실패한다(H2는 pgvector 확장도, `VECTOR` 타입도 모른다).

## 원인

- **로컬/운영**: `compose.yaml`이 `pgvector/pgvector:pg16`을 쓰므로 확장·타입이 있다.
- **테스트 런타임**: H2는 인메모리 경량 DB라 PostgreSQL 확장 생태계를 재현하지 못한다. pgvector는 C 확장이라 H2로 흉내 낼 수 없다.

즉 "테스트니까 H2로 빠르게"가 이 마이그레이션에는 성립하지 않는다.

## 해결

**마이그레이션·벡터 검증은 실제 PostgreSQL(pgvector)로만 수행한다.** H2는 pgvector와 무관한 단위 테스트용으로만 남긴다.

로컬 검증 절차:

```bash
# 1. pgvector 포함 PostgreSQL 기동
docker compose up -d postgres

# 2. 앱 기동(Flyway 자동 실행) 또는 마이그레이션만
./gradlew bootRun          # 기동 시 V1~V102 자동 적용
#   또는 flyway 플러그인 사용 시 ./gradlew flywayMigrate

# 3. 적용 확인
docker compose exec postgres psql -U ssafy -d pinlog -c "\dn"           # core, ai 스키마
docker compose exec postgres psql -U ssafy -d pinlog -c "\dt ai.*"      # ai 5테이블
docker compose exec postgres psql -U ssafy -d pinlog -c "\d ai.context_embedding"   # PK=context_id
docker compose exec postgres psql -U ssafy -d pinlog -c "SELECT extname FROM pg_extension WHERE extname='vector';"
```

CI·통합 테스트에서 마이그레이션을 검증하려면 **Testcontainers의 `pgvector/pgvector` 이미지**를 쓴다(H2 대체 아님, PostgreSQL 컨테이너).

## 재발 방지

- `db/migration/README.md`에 "H2는 pgvector 미지원, 검증은 PostgreSQL로만"을 명시.
- 벡터 의존 테스트에 H2 프로파일을 붙이지 않는다.
