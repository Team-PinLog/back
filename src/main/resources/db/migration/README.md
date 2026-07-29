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

현재 백엔드 구간은 `V2` member, `V3` core 도메인, `V4` social_account, `V5` Collection
`published_at` 백필·발행 시각 `CHECK` 순서입니다.

## 구간 소유의 대가 — out-of-order 허용

AI 구간이 백엔드 구간 **위**에 있으므로, AI 마이그레이션이 적용된 DB에서 백엔드가 새 번호를 추가하면 그 번호는 적용된 최대 버전보다 낮습니다. Flyway 기본값은 이를 거부하며 **애플리케이션 기동이 실패합니다.** 그래서 `spring.flyway.out-of-order: true`가 적용되어 있습니다.

- **적용 순서가 환경마다 다릅니다.** 빈 DB는 `V1 → V2 → V100`, 기존 DB는 `V1 → V100 → V102 → V2`입니다.
- 따라서 **다른 구간의 객체에 의존하는 마이그레이션을 쓰지 마세요.** 순서가 보장되지 않으므로 환경에 따라 실패합니다. 지금은 `core`와 `ai`가 서로를 정의하지 않아 안전합니다.
- 자세한 배경과 감수 항목은 [BD-26](../../../../docs/backend/decisions/BD-26-flyway-out-of-order.md), 증상은 [BT-02](../../../../docs/backend/troubleshooting/BT-02-flyway-out-of-order-version-ranges.md)에 있습니다.

## 소유권 주의 — `core.feed_event`

`core.feed_event`는 `core` 스키마에 있지만 **Feed 설계(AI 파트 소유)에서 나온 테이블**이라 `V102`(AI 구간)에 있습니다. 백엔드가 `V2~`에서 `core` 테이블 목록을 훑을 때 누락으로 오인해 **중복 정의하지 마세요.** 재정의하면 `already exists`로 마이그레이션이 깨집니다.

## 전제

- `V1`의 `CREATE EXTENSION vector`는 pgvector가 있어야 성공합니다. 로컬은 `compose.yaml`의 `pgvector/pgvector:0.8.5-pg16`, 운영 이미지는 infra 소관입니다.
- `CREATE EXTENSION IF NOT EXISTS`는 **이미 설치된 extension을 업그레이드하지 않습니다.** 이미지 버전을 올려도 기존 volume에서는 옛 extension 버전이 유지되므로 `ALTER EXTENSION vector UPDATE`가 별도로 필요합니다([데이터베이스 규약](../../../../docs/development/database-conventions.md)).
- `ai` 테이블의 데이터(Keyword Preset)는 SQL seed가 아니라 AI 서버 부트스트랩이 임베딩과 함께 적재합니다. Preset 소스는 `Team-PinLog/ai`의 `data/keyword_preset.yaml`입니다.
- 통합 테스트의 마이그레이션·벡터 검증은 Testcontainers의 PostgreSQL(pgvector)에서 수행합니다.
