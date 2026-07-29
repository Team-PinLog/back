# 결정 기록 (Decisions, ADR)

백엔드 도메인 내부의 기술 결정을 `BD-##` 번호로 기록합니다 — "왜 그렇게 정했나"와 그때 검토한 트레이드오프. 상태가 **Accepted**인 것은 확정된 결정이며 구현이 따라야 합니다.

## 파트 간 합의와의 경계

**파트 간 합의 자체는 여기서 정하지 않습니다.** 파트 간 영향이 있는 결정의 원본은 [`docs/ai/proposals/`](../../ai/proposals/)의 P 번호 절차와 공용 계약(`Team-PinLog/docs`의 `static/`)에 둡니다.

다만 그 합의를 **백엔드가 어떻게 수용했고 구현에서 무엇을 감수하는지**는 BD로 기록합니다. 이때 원본 근거를 옮겨 적지 않고 링크만 겁니다 — 같은 결정에 원본이 둘이 되면 반드시 어긋납니다.

| | 어디에 |
|---|---|
| 파트 간 합의의 원본 | P## · 공용 계약 `static/` |
| 백엔드 도메인 내부 결정 | BD |
| 합의의 수용과 백엔드 구현 경계 | BD (원본은 링크) |

번호는 `dev`에 머지된 기록을 기준으로 다음 값을 씁니다. 미머지 브랜치가 파일명으로 선점한 번호는 예약이 아니며, 머지 순서대로 확정됩니다.

## 보존 원칙

이 폴더는 결정 이력을 기록합니다. **폐기·대체된 결정도 삭제하지 않고 상태만 갱신합니다.** 회고에서 "왜 그때 그렇게 정했고, 왜 바꿨는가"를 추적하기 위함입니다.

- 결정 유지 → `상태: Accepted`
- 새 결정으로 대체 → 문서 유지 + `상태: Superseded by BD-##`
- 철회 → 문서 유지 + `상태: Rejected(사유)`
- 삭제 → 하지 않음. 잘못 작성된 문서도 정정으로 처리

## 헤더 형식

[`TEMPLATE.md`](TEMPLATE.md)를 복사해 시작합니다.

```text
- **상태**: Accepted | Proposed | Rejected | Superseded by BD-##
- **날짜**: 결정한 날
- **관련**: PR·커밋·Jira 링크
```

본문은 **맥락 → 선택지(트레이드오프) → 결정 → 결과** 순서입니다.

문서는 결정하면서 함께 씁니다([`CLAUDE.md`](../../../CLAUDE.md) 10번). 그러지 못하고 **나중에 쓰게 되면** 헤더에 한 줄을 덧붙여 그 사실을 드러냅니다. 이 줄이 없으면 결정할 때 쓴 문서입니다.

```text
- **작성 시점**: 2026-07-27 — 결정 이후에 정리
```

이때 `**날짜**`는 여전히 **결정한 날**이며, 날짜가 추정이면 근거가 되는 커밋·PR을 맥락에 밝힙니다.

## 이유의 성격을 밝힌다

「결정」 절에서 왜 그렇게 정했는지를 아래 넷 중 하나로 분류합니다. 나중에 쓰는 문서만의 규칙이 아닙니다 — 오늘 내리는 결정도 기본값을 그대로 쓴 것이거나 외부가 정해준 것일 수 있고, 그 구분이 나중에 되돌릴 때 값을 합니다.

| 분류 | 쓰는 방식 |
|---|---|
| 능동적 선택 | 대안을 실제로 검토함 → 선택지 표를 정상 작성 |
| 기본값 수용 | 프레임워크·생성기 기본을 그대로 씀 → "바꿀 이유를 찾지 못했다"고 적음 |
| 제약으로 주어짐 | 조직 표준·공용 계약·외부 요건이 정함 → 제약을 명시하고 그 **안에서** 무엇을 골랐는지 씀 |
| 근거 소실 | "당시 판단 근거를 재구성할 수 없다"고 적고 재검토 트리거를 강하게 검 |

**없는 이유를 지어내지 않습니다.** 이 기록의 가치는 정확성에 있고, 그럴듯하게 꾸며낸 이유 하나가 나머지 전부를 믿을 수 없게 만듭니다.

## 목록

| BD | 결정 | 상태 | 관련 |
|---|---|---|---|
| [BD-01](BD-01-h2-removal-testcontainers.md) | 테스트 런타임에서 H2 제거, PostgreSQL Testcontainers로 단일화 | Accepted | back#12 |
| [BD-02](BD-02-base-entity-common-columns.md) | BaseEntity는 created_at·deleted_at만 공유, updated_at 없음 | Accepted | S15P11A705-41 |
| [BD-03](BD-03-api-response-envelope.md) | 성공·오류 응답을 공통 envelope(ApiResponse)로 통일 | Accepted | S15P11A705-53 |
| [BD-04](BD-04-cursor-pagination.md) | 목록은 커서 기반 CursorPage + Base64(정렬키,id) 불투명 커서, size 기본 20·상한 100 | Accepted | S15P11A705-42 |
| [BD-05](BD-05-graceful-shutdown-timing.md) | graceful shutdown 타임아웃 20s, Kubernetes 쪽 값(`terminationGracePeriodSeconds` 40s·`preStop` 5s)은 Infra에 위임 | Accepted | S15P11A705-51 · [infra#33](https://github.com/Team-PinLog/infra/issues/33) |
| [BD-06](BD-06-framework-error-mapping.md) | GlobalExceptionHandler가 ResponseEntityExceptionHandler를 상속해 프레임워크 예외를 자체 상태로 매핑 | Accepted | S15P11A705-40 |
| [BD-07](BD-07-context-immutability.md) | Context 불변 — 수정은 삭제+생성. 버전 컬럼 제거, 수정 경합을 삭제 경합에 흡수 | Accepted | S15P11A705-76 |
| [BD-08](BD-08-soft-delete-no-restore.md) | 소프트 삭제 + 복구 없음 + 활성행 부분 유니크 | Accepted | S15P11A705-76 |
| [BD-09](BD-09-no-record-update-path.md) | Record 수정 경로를 두지 않는다 (공용 문서 정정 후속) | Accepted | S15P11A705-76 |
| [BD-10](BD-10-integrity-in-database.md) | 무결성을 앱이 아닌 DB에 — `CHECK`·부분 유니크·서로게이트 키 | Accepted | S15P11A705-76 |
| [BD-11](BD-11-minimum-holding-invariants.md) | 최소 보유 불변식 — 자동 연쇄 대신 409 확인 후 force, 잠금은 부모에 | Accepted | S15P11A705-76 |
| [BD-12](BD-12-duplicate-record-idempotent.md) | Collection 중복 Record 추가는 실패가 아닌 멱등 | Accepted | S15P11A705-76 |
| [BD-13](BD-13-public-boundary-query-dto-split.md) | 공개 경계를 쿼리·DTO 분리로 강제, 403 대신 404 | Accepted | S15P11A705-76 |
| [BD-14](BD-14-identifier-concealment.md) | 식별자 은닉 — `member.id` 비공개, Collection id를 진입점으로 | Accepted | S15P11A705-76 |
| [BD-15](BD-15-shelf-not-a-table.md) | Shelf·Library를 물리 테이블로 두지 않는다 | Accepted | S15P11A705-76 |
| [BD-16](BD-16-ai-derived-immediate-purge.md) | AI 파생 데이터는 즉시 무효화 표시 — 백엔드가 `ai` 스키마에 직접 쓰는 좁은 예외 | Accepted | S15P11A705-76 |
| [BD-17](BD-17-async-without-message-queue.md) | 비동기 AI를 큐 없이 DB State + Scheduler로 | Accepted | S15P11A705-76 |
| [BD-18](BD-18-keyword-preset-and-visibility.md) | Keyword는 프리셋에서만 · 3등급 공개 · 상위 집계 비저장 | Accepted | S15P11A705-76 |
| [BD-19](BD-19-place-snapshot.md) | Place는 공용 스냅샷 — 갱신·자동 병합 금지, 검색은 프론트가 직접 | Accepted | S15P11A705-76 |
| [BD-20](BD-20-selective-denormalization.md) | 선택적 비정규화 — `record_count`는 두고 팔로워 수는 두지 않는다 | Accepted | S15P11A705-76 |
| [BD-21](BD-21-auth-token-model.md) | 인증 토큰 — JWT Access 30분 / Refresh 7일, Refresh는 Redis | Accepted | S15P11A705-76 |
| [BD-22](BD-22-signup-commit-point.md) | 가입 확정 시점 — 약관 동의 전에는 `member`를 만들지 않는다 | Accepted | S15P11A705-76 |
| [BD-23](BD-23-collection-auto-publish.md) | Collection 자동 발행 + `is_published` 컬럼 유지 | Accepted | S15P11A705-76 |
| [BD-24](BD-24-foundation-reset.md) | 파운데이션 리셋 — 초기 설정을 최신 `dev`에서 작은 PR로 다시 구성 | Accepted | Issue #9 |
| [BD-25](BD-25-context-origin-created-at.md) | Context 최초 작성 시각 보존 — `origin_created_at`, 목록은 오래된순 (BD-07 트리거 발동) | Accepted | docs#16 |
| [BD-26](BD-26-flyway-out-of-order.md) | 버전 구간 소유를 유지하고 Flyway `out-of-order`를 허용 (BT-02 해소) | Accepted | S15P11A705-86 |
| [BD-27](BD-27-coverage-gate-bundle-80.md) | 커버리지 게이트를 BUNDLE 기준 LINE·BRANCH 80%로, 진입점은 집계 제외 | Accepted | S15P11A705-103 |
| [BD-28](BD-28-readiness-includes-db.md) | readiness 그룹에 `db`를 넣고 `redis`는 넣지 않는다 | Accepted | S15P11A705-106 |
| [BD-29](BD-29-nullmarked-security-package.md) | `global/security`를 패키지 단위 `@NullMarked`로 선언(파라미터별 `@NonNull` 대신) | Accepted | S15P11A705-63 |
| [BD-30](BD-30-authorization-request-in-cookie.md) | 인가 요청(state·PKCE verifier)을 `HttpSession` 대신 쿠키에 — 서명 없이 역직렬화 허용목록으로 | Accepted | S15P11A705-63 |
| [BD-31](BD-31-jwt-rs256-key-management.md) | 세션 JWT를 RS256으로 서명, 키는 환경변수 주입 + 운영 fail-fast (`kid` 선반영, JWKS 없음) | Accepted | S15P11A705-63 |
| [BD-32](BD-32-refresh-reuse-no-family-revocation.md) | Refresh 재사용을 감지해도 세션 계열을 폐기하지 않는다 (당분간) — 감지·WARN만, 폐기는 후속 | Superseded by BD-35 | S15P11A705-63 |
| [BD-33](BD-33-published-at-database-invariant.md) | 발행된 Collection의 `published_at`을 DB `CHECK`로 강제 | Accepted | S15P11A705-125 |
| [BD-34](BD-34-feed-deterministic-pagination-without-session-cache.md) | Feed 페이지네이션을 Redis Session Cache 대신 `requestId` seed 기반 결정적 재계산으로 | Accepted | S15P11A705-120 |
| [BD-35](BD-35-refresh-reuse-family-revocation.md) | Refresh 재사용을 유출로 간주해 그 회원의 세션을 전부 폐기 — 회원별 `jti` 인덱스, 오탐 시 전체 로그아웃 감수 | Accepted | S15P11A705-131 |
| [BD-37](BD-37-ai-derived-invalidation-inside-deletion-transaction.md) | AI 파생 데이터 무효화를 삭제 트랜잭션 안에서 백엔드가 직접 쓴다 | Accepted | S15P11A705-124 |
