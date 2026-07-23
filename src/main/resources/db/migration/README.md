# DB Migration (Flyway)

Flyway 마이그레이션 위치입니다. 여러 파트가 각자 마이그레이션을 추가하므로, 버전 충돌을 막기 위해 **파트별 번호 구간**을 예약합니다.

## 버전 구간 컨벤션

| 구간 | 소유 | 내용 |
|---|---|---|
| `V1` | 공통 기반 | `core`·`ai` 스키마 생성, `CREATE EXTENSION vector` |
| `V2`~`V99` | 백엔드 | `core` 도메인 테이블·제약 (member, place, record, context, collection, collection_record, follow …) |
| `V100`~`V199` | AI 파트 | `ai` 스키마 테이블·인덱스, `core.feed_event` |
| `V200`~ | 예약됨 / 미할당 | 현재 소유 규칙이 없으므로 사용 전 소유자와 번호를 합의 |

규칙:

- **자기 구간 밖 번호는 쓰지 않습니다.** (남의 파일을 수정하지 않는 것과 같은 취지)
- `V200` 이후는 현재 예약됨 / 미할당 상태입니다. 해당 번호를 쓰기 전 관련 소유자와 책임 및 번호를 합의합니다.
- 이미 배포된 마이그레이션은 수정하지 않습니다. 변경이 필요하면 새 버전을 추가합니다.

## 소유권 주의 — `core.feed_event`

`core.feed_event`는 `core` 스키마에 있지만 **Feed 설계(AI 파트 소유)에서 나온 테이블**이라 `V102`(AI 구간)에 있습니다. 백엔드가 `V2~`에서 `core` 테이블 목록을 훑을 때 누락으로 오인해 **중복 정의하지 마세요.** 재정의하면 `already exists`로 마이그레이션이 깨집니다.

## 전제

- `V1`의 `CREATE EXTENSION vector`는 pgvector가 있어야 성공합니다. 로컬은 `compose.yaml`의 `pgvector/pgvector:0.8.1-pg16`, 운영 이미지는 infra 소관입니다.
- `ai` 테이블의 데이터(Keyword Preset)는 SQL seed가 아니라 AI 서버 부트스트랩이 임베딩과 함께 적재합니다. Preset 소스는 `Team-PinLog/ai`의 `data/keyword_preset.yaml`입니다.
- 통합 테스트의 마이그레이션·벡터 검증은 Testcontainers의 PostgreSQL(pgvector)에서 수행합니다.
