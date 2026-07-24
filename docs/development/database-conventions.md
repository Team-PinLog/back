# 데이터베이스 개발 규약

시작 절차와 PR 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 PostgreSQL schema, Flyway migration과 DB 테스트의 상세 기준입니다.

## 지원 데이터베이스

PostgreSQL만 지원합니다. 로컬 실행, 통합 테스트와 CI는 `pgvector/pgvector:0.8.1-pg16` PostgreSQL을 기준으로 합니다. H2를 추가하거나 PostgreSQL 전용 migration의 대체 검증으로 사용하지 않습니다.

애플리케이션의 Hibernate 설정은 `ddl-auto=validate`입니다. schema 변경은 Hibernate 자동 생성이 아니라 Flyway migration으로 관리합니다.

## Flyway 버전과 소유 경계

| 버전 | 소유 | 용도 |
| --- | --- | --- |
| `V1` | 공통 기반 | `core`·`ai` schema와 `vector` extension의 초기 기반 |
| `V2`~`V99` | 백엔드 | `core` 도메인 테이블과 제약 |
| `V100`~`V199` | AI | AI schema, AI 소유 인덱스와 `core.feed_event` |

자기 소유 구간만 사용합니다. `core` schema에 있어도 `core.feed_event`는 AI가 소유한 V102 migration이므로 재정의하지 않습니다. 표 밖의 버전이 필요하면 파일을 만들기 전에 관련 소유자와 번호 및 책임을 합의합니다.

이미 적용된 migration은 절대로 수정하지 않습니다. 변경이 필요하면 다음 새 버전 migration을 추가합니다. 현재 파일과 적용 순서의 구현 세부는 [db/migration README](../../src/main/resources/db/migration/README.md)에서 확인합니다.

## migration과 DB 변경 검증

- migration을 추가하거나 변경하는 PR은 빈 PostgreSQL DB에서 전체 migration이 적용되는 Testcontainers 검증을 추가하거나 갱신합니다.
- Repository, Flyway와 PostgreSQL 기능을 쓰는 테스트는 PostgreSQL Testcontainers를 사용합니다.
- DB 변경 PR은 `./gradlew clean check --no-daemon`을 실행해 PostgreSQL 통합 테스트를 통과해야 합니다.
- Docker가 실행되지 않으면 DB 테스트를 skip하지 않습니다. 원인을 표시해 실패하게 하고 Docker를 시작한 뒤 다시 실행합니다.
