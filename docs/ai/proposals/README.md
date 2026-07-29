# 제안·결정 (Proposals)

이 폴더의 문서 중 상태가 **Accepted**인 것은 확정된 결정이며 구현이 따라야 한다. **Driver**는 스코프가 아니라 제안·주도 파트다.

이 폴더는 **back 레포의 결정**이다 — 어느 파트가 주도했든 back 아티팩트(`build.gradle`·`application.yml`·`db/migration`)에 영향을 주는 결정을 `P##` 번호로 기록한다. `P##`는 전수 인벤토리의 제안 번호와 일치한다.

## 헤더 형식

```text
- **상태**: Accepted | Proposed | Rejected | Superseded by P-XX
- **날짜**:
- **주도(Driver)**:
- **관련 PR/커밋**:
```

## 개별 문서

| P | 제목 | 상태 | Driver |
|---|---|---|---|
| [P21](P21-flyway-migration-convention.md) | Flyway 마이그레이션 파트별 번호 구간 | Accepted | AI |
| [P22](P22-feed-event-ownership.md) | `core.feed_event`를 AI 구간(V102)에 배치 | Accepted | AI |
| [P24](P24-flyway-schemas-unspecified.md) | `flyway.schemas` 미지정 — 이력 테이블을 public에 | Accepted | 백엔드 |
| [P42](P42-feed-mvp-without-place-metadata.md) | MVP Feed에서 Place category·region 제외 | Accepted | AI |

## 백엔드 관련 제안 — 전수

| P | 결정 | 상태 | 반영처 |
|---|---|---|---|
| P10 | FAILED Finalizer — Spring이 retry 소진 작업을 FAILED로 종결(FastAPI 대행 안 함) | Accepted | [spec/ai-rescan-scheduler.md](../spec/ai-rescan-scheduler.md) |
| P13 | 상태 쓰기 책임 분담 — Spring이 PENDING/CANCELLED/retry_count/is_deleted/FAILED 소유 | Accepted | [spec/context-state-sync.md](../spec/context-state-sync.md) |
| P21 | Flyway 파트별 번호 구간(V1 공통/V2~99 백엔드/V100~199 AI/V200+ 선점) | Accepted | [P21](P21-flyway-migration-convention.md) |
| P22 | `core.feed_event` 소유=AI, V102 배치, 백엔드 V2~ 중복정의 금지 | Accepted | [P22](P22-feed-event-ownership.md) |
| P23 | `feed_event` FK 없음(append-only 로그, 값 보유) | Accepted | [spec/feed-event.md](../spec/feed-event.md), [P22](P22-feed-event-ownership.md) |
| P24 | `flyway.schemas` 미지정 → 이력 public | Accepted | [P24](P24-flyway-schemas-unspecified.md) |
| P25 | `V1` 스키마 `IF NOT EXISTS` 없이 생성(경계 보호), extension만 IF NOT EXISTS | Accepted | [implements](../implements/2026-07-23-flyway-ai-schema-migration.md) |
| P33 | back/docs README 백엔드 공통 허브화(AI 편중 시정) | Accepted | [../README.md](../README.md) |
| P39 | compose 이미지 `postgres:latest` → `pgvector/pgvector:pg16` | Accepted | [implements](../implements/2026-07-23-flyway-ai-schema-migration.md) |
| P42 | MVP Feed 후보·점수·Cache에서 Place category·region 제외 | Accepted | [P42](P42-feed-mvp-without-place-metadata.md) |

## 미결 (백엔드 관련)

| M | 쟁점 | 상태 |
|---|---|---|
| M2 | Context 목록 `created_at` 시각 기준(최초 작성 vs 현재), `origin_context_id` 계보 필드 여부 — **core `V2~` migration blocker** | 미결 |

> 전체 미결(M1·M3~M7)은 AI 레포 `docs/proposals/README.md`의 미결 표를 참조.
