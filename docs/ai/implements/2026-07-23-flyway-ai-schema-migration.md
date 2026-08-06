# 작업 리포트 — Flyway 도입 + ai 스키마·feed_event 마이그레이션

- **상태**: 완료
- **날짜**: 2026-07-23
- **PR**: [back#3](https://github.com/Team-PinLog/back/pull/3) — `feat: Flyway 도입 + ai 스키마·feed_event 마이그레이션 (V1/V100~102)`
- **주요 커밋**: `946df11` (merge `23f1933`)
- **브랜치**: `feat/ai-schema-migration` ← `dev`
- **주도(Driver)**: AI 파트(스키마·feed_event·컨벤션) / 백엔드 공통(Flyway 도입)

## 목표

계약(`docs/static/05_AI_설계.md` §12)은 "실행 가능한 DDL은 back 마이그레이션이 원본"이라 정한다. 이 시점에 백엔드에는 Flyway·datasource·마이그레이션이 전무했고, `ai` 스키마·`core.feed_event`·Keyword Preset이 문서로만 존재했다. 이 리포트는 그중 **AI 파트 소유분**(ai 스키마 + feed_event + Flyway 기반)을 실행 가능하게 만든 작업을 정리한다. `core` 도메인(member/place/record/context/collection…)은 백엔드 영역이라 제외했다.

## 산출물

### 빌드·설정
- `build.gradle`: `flyway-core`, `flyway-database-postgresql` 추가.
- `application.yml`: `spring.flyway`(enabled, locations, **schemas 미지정**), `jpa.hibernate.ddl-auto: validate`. schemas를 지정하지 않은 근거는 [P24](../proposals/P24-flyway-schemas-unspecified.md).
- `compose.yaml`: 이미지를 `postgres:latest`에서 `pgvector/pgvector:pg16`으로 교체해 로컬에서도 pgvector를 쓸 수 있게 했다.

### 마이그레이션 (`src/main/resources/db/migration/`)
| 버전 | 파일 | 내용 |
|---|---|---|
| `V1` | `V1__create_schemas.sql` | `core`·`ai` 스키마(`IF NOT EXISTS` 없이), `CREATE EXTENSION IF NOT EXISTS vector` |
| `V100` | `V100__ai_tables.sql` | `ai` 5테이블 — `keyword_preset`, `context_ai_state`(embedding/keyword 두 status), `context_embedding`(PK `context_id`), `context_keyword`, `context_keyword_analysis` |
| `V101` | `V101__ai_indexes.sql` | 5개 인덱스(user_active, record, 두 status, keyword) |
| `V102` | `V102__feed_event.sql` | `core.feed_event`(append-only, FK 없음). 소유권 근거는 [P22](../proposals/P22-feed-event-ownership.md) |

- `db/migration/README.md`: 버전 구간 컨벤션([P21](../proposals/P21-flyway-migration-convention.md)) + feed_event 소유권 고지.
- 버전 컨벤션: `V1` 공통 / `V2~99` 백엔드 / `V100~199` AI / `V200~` 선점.

## 검증

`pgvector/pgvector:pg16` 컨테이너에서 실측:

- [x] `V1 → V100 → V101 → V102` 순차 적용 성공.
- [x] `ai.*` 5테이블 + `core.feed_event` 생성 확인.
- [x] `\d ai.context_embedding`으로 **PK가 `context_id` 단독**임을 확인. UPSERT `ON CONFLICT (context_id)`가 성립하기 위한 조건이다.
- [x] `SELECT extname FROM pg_extension WHERE extname='vector'`로 확장 존재 확인.
- [x] `V1`의 `CREATE SCHEMA`를 재실행하면 `already exists`로 실패한다. 스키마 소유 경계가 실제로 보호됨을 확인했다.
- [x] 기존 `DeploymentContractTests` 통과 유지(actuator 경로 무관).

## 설계 근거 요약

- `context_embedding` PK를 `context_id` **단독**으로 둔 이유: 재처리 시 `ON CONFLICT (context_id) DO UPDATE` UPSERT가 성립해야 한다. `is_deleted`를 PK에 넣으면 복합 PK가 되어 UPSERT가 깨진다. `is_deleted`는 일반 컬럼이며, Spring이 쓰는 소프트 삭제 마커다.
- `keyword_preset`은 `embedding VECTOR(1536) NOT NULL`이라 SQL seed가 불가능하다. 데이터는 AI 서버 부트스트랩이 임베딩과 함께 적재하며, 소스는 `Team-PinLog/ai`의 `data/keyword_preset.yaml`이다. 그래서 Flyway seed 번호는 없다.

## 관련 결정

- [P21 Flyway 번호 컨벤션](../proposals/P21-flyway-migration-convention.md)
- [P22 feed_event 소유권](../proposals/P22-feed-event-ownership.md)
- [P24 flyway.schemas 미지정](../proposals/P24-flyway-schemas-unspecified.md)

## 후속

- 백엔드 `core` 도메인 마이그레이션(`V2~`)은 백엔드 파트 담당이다.
- 운영 postgres 이미지 pgvector화와 `ai` 전용 DB role 분리는 infra 파트 담당이다(`docs/static/05-1` §2).
