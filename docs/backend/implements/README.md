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
| BI-08 | core 스키마 마이그레이션과 도메인 엔티티·리포지토리 기반 구축 (S15P11A705-66) | ✅ 완료 | [BI-08](BI-08-2026-07-28-core-domain-foundation.md) |
| BI-09 | Record·Context API와 인증 스텁, 지도 마커 (S15P11A705-67) | ✅ 완료 | [BI-09](BI-09-2026-07-28-record-context-api.md) |
| BI-10 | Collection 생성·조회·편집 API (S15P11A705-68) | ✅ 완료 | [BI-10](BI-10-2026-07-28-collection-api.md) |
| BI-11 | Follow와 Shelf 기반 Library 조회 API (S15P11A705-69) | ✅ 완료 | [BI-11](BI-11-2026-07-28-follow-library-api.md) |
| BI-12 | Record/Context/Collection 삭제와 연쇄 트랜잭션 (S15P11A705-70) | ✅ 완료 | [BI-12](BI-12-2026-07-28-deletion-cascade.md) |
| BI-13 | 타인 조회 공개 범위 필터와 식별자 은닉 (S15P11A705-71) | ✅ 완료 | [BI-13](BI-13-2026-07-28-public-scope-filter.md) |
| BI-14 | 같은 장소 동시 저장을 충돌 없는 INSERT로 수렴 (S15P11A705-105) | ✅ 완료 | [BI-14](BI-14-2026-07-28-record-create-conflict-free-insert.md) |
| BI-15 | 중복 팔로우 경합을 500이 아니라 409로 거절 (S15P11A705-104) | ✅ 완료 | [BI-15](BI-15-2026-07-28-duplicate-follow-race.md) |
| BI-16 | 요청 입력 크기 상한 — recordIds 100개·Context 본문 500자 (S15P11A705-117) | ✅ 완료 | [BI-16](BI-16-2026-07-28-input-size-limits.md) |
| BI-17 | 별칭 수정 요청의 키 생략 동작을 계약으로 고정 (S15P11A705-116) | ✅ 완료 | [BI-17](BI-17-2026-07-28-alias-key-omission-contract.md) |
| BI-18 | 쿠키 기반 세션 JWT — RS256 발급·검증 필터·Redis Refresh 회전 (S15P11A705-63) | ✅ 완료 | [BI-18](BI-18-2026-07-28-jwt-cookie-session.md) |
| BI-19 | Collection `published_at` DB 불변식 (back#58) | ✅ 완료 | [BI-19](BI-19-2026-07-28-published-at-invariant.md) |
| BI-20 | Feed 추천 MVP — 후보 3채널·결정적 점수·opaque cursor (S15P11A705-120) | ✅ 완료 | [BI-20](BI-20-2026-07-29-feed-recommendation-mvp.md) |
| BI-21 | Refresh 재사용 시 회원 단위 세션 폐기 — 회원별 `jti` 인덱스 (S15P11A705-131) | ✅ 완료 | [BI-21](BI-21-2026-07-29-refresh-reuse-family-revocation.md) |
| BI-23 | 삭제 경로의 AI 파생 데이터 무효화 — `context_ai_state` CANCELLED·`context_embedding.is_deleted` (S15P11A705-124) | ✅ 완료 | [BI-23](BI-23-2026-07-29-ai-derived-invalidation-on-delete.md) |
| BI-24 | Kakao·Naver 소셜 로그인 추가 — 공급자별 사용자 정보 정규화 (S15P11A705-64) | ✅ 완료 | [BI-24](BI-24-2026-07-29-kakao-naver-login.md) |
| BI-25 | 개인 자연어 검색 백엔드 연동 — FastAPI 호출·Core 재검증·Record 단위 조립 (S15P11A705-135) | ✅ 완료 | [BI-25](BI-25-2026-07-29-personal-search-backend-integration.md) |
| BI-26 | 운영 Secret 5개를 Infra SealedSecret PR로 전달하는 수동 workflow (S15P11A705-154) | ✅ 완료 | [BI-26](BI-26-2026-07-29-runtime-secret-workflow.md) |
| BI-27 | 소셜 로그인 이메일 필수화 — 컬럼 NOT NULL·정규화 거절 (S15P11A705-152) | ✅ 완료 | [BI-27](BI-27-2026-07-30-social-login-email-required.md) |
| BI-28 | 재스캔 Scheduler와 FAILED Finalizer — 유실·정지된 AI 처리 복구 (S15P11A705-159) | ✅ 완료 | [BI-28](BI-28-2026-07-30-ai-rescan-scheduler.md) |
| BI-29 | 회원 탈퇴 — 마스킹·연쇄 소프트 삭제·AI 무효화·세션 폐기 (S15P11A705-65) | ✅ 완료 | [BI-29](BI-29-2026-07-30-member-withdrawal.md) |
| BI-30 | 마이페이지 요약 조회 — 계정 정보와 활성 기준 카운트 4 (S15P11A705-200) | ✅ 완료 | [BI-30](BI-30-2026-07-31-me-summary.md) |
| BI-31 | 소셜 로그인 진단 로그 — 성공·가입·실패를 짝지어 볼 수 있게 (S15P11A705-186) | ✅ 완료 | [BI-31](BI-31-2026-07-31-login-diagnostics.md) |
