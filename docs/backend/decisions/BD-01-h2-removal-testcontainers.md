# BD-01. 테스트 런타임에서 H2 제거, PostgreSQL Testcontainers로 단일화

- **상태**: Accepted
- **날짜**: 2026-07-23
- **관련**: back#12 (`564f3e4`), [BI-01](../implements/BI-01-2026-07-23-postgres-testcontainers-migration-tests.md)

## 맥락

Flyway 도입(back#3) 이후 마이그레이션이 빈 DB에 온전히 적용되는지 테스트로 검증할 필요가 있었다. 그런데 테스트 런타임은 `runtimeOnly 'com.h2database:h2'`였고, `V1`은 `CREATE EXTENSION vector`를 실행하며 `ai` 스키마 테이블은 `VECTOR(1536)` 컬럼을 갖는다. H2는 pgvector 확장도 `VECTOR` 타입도 지원하지 않아, H2에서는 마이그레이션 검증 자체가 불가능했다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) H2 유지, 마이그레이션 테스트 포기 | 테스트 빠름, Docker 불필요 | 마이그레이션 회귀를 CI가 못 잡음. 운영과 다른 DB로 테스트하는 착시 지속 |
| (b) H2 유지 + 마이그레이션만 PostgreSQL 병행 | 단위 테스트는 계속 빠름 | 두 DB 방언을 계속 관리. "어떤 테스트가 어느 DB인가" 혼선, H2 전용 설정 부채 |
| (c) H2 완전 제거 + Testcontainers 단일화 | 테스트 환경 = 운영 환경(pgvector 포함). 방언 분기 제거 | 테스트 실행에 Docker 필수, 컨테이너 기동만큼 느려짐 |

## 결정

**(c)를 채택한다.** `ai` 스키마가 pgvector에 의존하는 이상 H2 병행은 "검증 안 되는 절반"을 남기는 구조라, 재현성을 속도보다 우선했다. 컨테이너 이미지는 로컬 compose와 동일 계열인 `pgvector/pgvector:0.8.1-pg16`로 고정한다.

## 결과

- 감수하는 것: 테스트 실행에 Docker Desktop 필수(루트 README에 명시), 통합 테스트 기동 시간 증가.
- 규약으로 승격: [`docs/development/database-conventions.md`](../../development/database-conventions.md) — "H2 추가 금지, migration PR은 PostgreSQL Testcontainers 검증 추가".
- 재검토 트리거: CI에서 Docker 사용이 불가능해지거나, 컨테이너 기동 시간이 개발 루프를 실질적으로 막을 때.
- 과정 기록: 문제 발견 경위는 AI 파트 트러블슈팅 [T9](../../ai/troubleshooting/h2-pgvector-incompat.md) 참조.
