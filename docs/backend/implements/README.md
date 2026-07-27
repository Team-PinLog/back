# 구현 리포트 (Implements)

무엇을 만들었고 어떻게 검증했는지 `BI-##` 번호로 기록합니다. [`spec/`](../spec/)이 "무엇을 만들 것인가"라면, 여기는 "어떻게 만들었나"와 검증 결과입니다. 파일명은 `BI-##-YYYY-MM-DD-<주제>.md`.

번호는 `dev`에 머지된 기록을 기준으로 다음 값을 씁니다. 미머지 브랜치가 파일명으로 선점한 번호는 예약이 아니며, 머지 순서대로 확정됩니다.

## 보존 원칙

이 폴더는 구현 이력을 기록합니다. **완료된 항목도 삭제하지 않고 상태 표시만 갱신합니다.** 회고·복기에서 "무엇을 어떻게 만들었는가"를 추적하기 위함입니다.

- 완료 → 문서 유지 + `상태: 완료`
- 무효화 → 문서 유지 + `상태: 무효(사유)` 표기
- 삭제 → 하지 않음. 잘못 작성된 문서도 정정으로 처리

## 목록

| BI | 산출 | 상태 | 문서 |
|---|---|---|---|
| BI-01 | PostgreSQL Testcontainers 마이그레이션 검증 + H2 제거 (back#12) | ✅ 완료 | [BI-01](BI-01-2026-07-23-postgres-testcontainers-migration-tests.md) |
| BI-02 | Member 엔티티 + BaseEntity(soft delete) 구현 (S15P11A705-41) | ✅ 완료 | [BI-02](BI-02-2026-07-27-member-base-entity-soft-delete.md) |
| BI-03 | 성공·오류 응답 공통 envelope(ApiResponse) 구현 (S15P11A705-53) | ✅ 완료 | [BI-03](BI-03-2026-07-27-api-response-envelope.md) |
| BI-04 | 커서 기반 목록 응답 공용 타입(Cursor, CursorPage) 구현 (S15P11A705-42) | ✅ 완료 | [BI-04](BI-04-2026-07-27-cursor-pagination.md) |
| BI-05 | SIGTERM graceful shutdown + liveness·readiness probe 계약 테스트 (S15P11A705-51) | ✅ 완료 | [BI-05](BI-05-2026-07-27-graceful-shutdown.md) |
| BI-06 | Infra 배포 연동 체크리스트 검증 + pgvector `0.8.5-pg16` 정렬 (S15P11A705-51) | ✅ 완료 | [BI-06](BI-06-2026-07-27-deployment-contract-verification.md) |
| BI-07 | 프레임워크 예외를 catch-all 500이 아니라 자체 상태 코드로 매핑 (S15P11A705-40) | ✅ 완료 | [BI-07](BI-07-2026-07-27-framework-error-mapping.md) |
