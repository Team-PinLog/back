# WORKLOG — Backend 파트

시간순 작업 로그입니다. 유형별 폴더(spec/decisions/implements/troubleshooting) 분산으로 인한 "내 작업 추적" 비용을 시간축 인덱스로 상쇄합니다. **이후 작업마다 한 줄씩 추가**합니다. 커밋 메시지의 중복이 아니라 **의도와 결정의 로그**로 씁니다.

| 날짜 | 작업 | 관련 문서 |
|---|---|---|
| 2026-07-23 | H2 제거 + Testcontainers(PostgreSQL/pgvector) 마이그레이션 검증 전환 (back#12) | [BD-01](decisions/BD-01-h2-removal-testcontainers.md) · [BI-01](implements/BI-01-2026-07-23-postgres-testcontainers-migration-tests.md) |
| 2026-07-26 | 백엔드 파트 문서 체계 신설 — spec/decisions/implements/troubleshooting + WORKLOG (S15P11A705-38) | 이 트리 전체 |
| 2026-07-27 | (Task 1) `core.member` V2 마이그레이션 추가 — id·created_at·deleted_at 3컬럼, 활성 회원 부분 인덱스 (`e755b1e`, S15P11A705-41) | [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) |
| 2026-07-27 | (Task 2) `BaseEntity`(created_at·deleted_at 공유, updated_at 없음) + `JpaAuditingConfig` + `Member`/`MemberRepository` 구현 (`0f3c4d6`, S15P11A705-41) | [BD-02](decisions/BD-02-base-entity-common-columns.md) · [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) |
| 2026-07-27 | (Task 3) Member soft delete 검증 테스트 작성 — `repository.delete()`가 물리 삭제 대신 `deleted_at`을 기록하고 조회에서 제외됨을 확인, raw SQL로 영속된 `deleted_at` 검증을 강화 (`d1204bf`, `02fcaa7`, S15P11A705-41) | [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) |
| 2026-07-27 | (Task 4) `./gradlew clean check` 전체 게이트를 처음 실행해 공유 Testcontainers 컨테이너가 클래스마다 재시작되던 버그를 발견·수정(싱글톤 컨테이너 패턴, `6c61272`) + 로컬 프로파일(`application-local.yml`) 추가 + `database-conventions.md` 공통 컬럼 규약, `configuration.md` 값 정렬(`1acd804`, `67bcd11`) (S15P11A705-41) | [BD-02](decisions/BD-02-base-entity-common-columns.md) · [BT-01](troubleshooting/BT-01-shared-testcontainers-lifecycle.md) |
| 2026-07-27 | (Task 5) BaseEntity 공통 컬럼 결정·구현 리포트·Testcontainers 트러블슈팅 기록 (S15P11A705-41) | [BD-02](decisions/BD-02-base-entity-common-columns.md) · [BI-02](implements/BI-02-2026-07-27-member-base-entity-soft-delete.md) · [BT-01](troubleshooting/BT-01-shared-testcontainers-lifecycle.md) |
| 2026-07-27 | (Task 1) `ApiResponse<T>` 공통 envelope 레코드 추가, 운영 Jackson 3 매퍼로 JSON 검증 (`a45475a`, `93f4ad6`, S15P11A705-53) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
| 2026-07-27 | (Task 2) `ApiResponseBodyAdvice`로 `domain` 패키지 컨트롤러 응답 자동 감싸기, actuator·springdoc 제외 (`fe52533`, `86b0dd6`, S15P11A705-53) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
| 2026-07-27 | (Task 3) `GlobalExceptionHandler` 네 분기가 `ApiResponse.fail(error)` 반환하도록 전환, 상태·코드·로그 레벨 변경 없음 (`a1cd90f`, S15P11A705-53) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
| 2026-07-27 | (Task 4) 규약 문서(`api-conventions.md`·`error-handling.md`) 갱신 + BD-03·BI-03 기록 + `clean check` 전체 게이트 통과 (`c1ab841`, `e43812d`, S15P11A705-53) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
| 2026-07-27 | (Task 5) `ApiResponseOpenApiCustomizer`(springdoc `OperationCustomizer`)로 `/v3/api-docs` 2xx 스키마에도 envelope 반영, BD-03·BI-03 정정 (S15P11A705-53) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
