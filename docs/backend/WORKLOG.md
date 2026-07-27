# WORKLOG — Backend 파트

시간순 작업 로그입니다. 유형별 폴더(spec/decisions/implements/troubleshooting) 분산으로 인한 "내 작업 추적" 비용을 시간축 인덱스로 상쇄합니다. **이후 작업마다 한 줄씩 추가**합니다. 커밋 메시지의 중복이 아니라 **의도와 결정의 로그**로 씁니다.

| 날짜 | 작업 | 관련 문서 |
|---|---|---|
| 2026-07-23 | H2 제거 + Testcontainers(PostgreSQL/pgvector) 마이그레이션 검증 전환 (back#12) | [BD-01](decisions/BD-01-h2-removal-testcontainers.md) · [BI-01](implements/BI-01-2026-07-23-postgres-testcontainers-migration-tests.md) |
| 2026-07-26 | 백엔드 파트 문서 체계 신설 — spec/decisions/implements/troubleshooting + WORKLOG (S15P11A705-38) | 이 트리 전체 |
| 2026-07-27 | Task 1: `ApiResponse<T>` 공통 봉투 레코드 추가, 운영 Jackson 3 매퍼로 JSON 검증 (S15P11A705-53, `a45475a`+`93f4ad6`) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
| 2026-07-27 | Task 2: `ApiResponseBodyAdvice`로 `domain` 패키지 컨트롤러 응답 자동 감싸기, actuator·springdoc 제외 (S15P11A705-53, `fe52533`+`86b0dd6`) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
| 2026-07-27 | Task 3: `GlobalExceptionHandler` 네 분기가 `ApiResponse.fail(error)` 반환하도록 전환, 상태·코드·로그 레벨 변경 없음 (S15P11A705-53, `a1cd90f`) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
| 2026-07-27 | Task 4: 규약 문서(`api-conventions.md`·`error-handling.md`) 갱신 + BD-03·BI-03 기록 + `clean check` 전체 게이트 통과 확인 (S15P11A705-53, 이 커밋) | [BD-03](decisions/BD-03-api-response-envelope.md) · [BI-03](implements/BI-03-2026-07-27-api-response-envelope.md) |
