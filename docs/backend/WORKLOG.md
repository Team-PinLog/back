# WORKLOG — Backend 파트

시간순 작업 로그입니다. 유형별 폴더(spec/decisions/implements/troubleshooting) 분산으로 인한 "내 작업 추적" 비용을 시간축 인덱스로 상쇄합니다. **이후 작업마다 한 줄씩 추가**합니다. 커밋 메시지의 중복이 아니라 **의도와 결정의 로그**로 씁니다.

| 날짜 | 작업 | 관련 문서 |
|---|---|---|
| 2026-07-23 | H2 제거 + Testcontainers(PostgreSQL/pgvector) 마이그레이션 검증 전환 (back#12) | [BD-01](decisions/BD-01-h2-removal-testcontainers.md) · [BI-01](implements/BI-01-2026-07-23-postgres-testcontainers-migration-tests.md) |
| 2026-07-26 | 백엔드 파트 문서 체계 신설 — spec/decisions/implements/troubleshooting + WORKLOG (S15P11A705-38) | 이 트리 전체 |
| 2026-07-27 | (Task 1) `core.member` V2 마이그레이션 추가 — id·created_at·deleted_at 3컬럼, 활성 회원 부분 인덱스 (S15P11A705-41) | [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) |
| 2026-07-27 | (Task 2) `BaseEntity`(created_at·deleted_at 공유, updated_at 없음) + `JpaAuditingConfig` + `Member`/`MemberRepository` 구현 (S15P11A705-41) | [BD-02](decisions/BD-02-base-entity-common-columns.md) · [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) |
| 2026-07-27 | (Task 3) Member soft delete 검증 테스트 작성 중 공유 Testcontainers 컨테이너 재시작 버그 발견·수정(싱글톤 컨테이너 패턴) (S15P11A705-41) | [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) · [BT-01](troubleshooting/BT-01-shared-testcontainers-lifecycle.md) |
| 2026-07-27 | (Task 4) 로컬 프로파일(`application-local.yml`) 추가 + `database-conventions.md` 공통 컬럼 규약, `configuration.md` 값 정렬 (S15P11A705-41) | [BD-02](decisions/BD-02-base-entity-common-columns.md) |
| 2026-07-27 | (Task 5) BaseEntity 공통 컬럼 결정·구현 리포트·Testcontainers 트러블슈팅 기록 (S15P11A705-41) | [BD-02](decisions/BD-02-base-entity-common-columns.md) · [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) · [BT-01](troubleshooting/BT-01-shared-testcontainers-lifecycle.md) |
