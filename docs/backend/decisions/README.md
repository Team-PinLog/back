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
- **날짜**: 결정 시점(작성 시점이 아님)
- **기록**: 당시 | 소급 (작성일)
- **관련**: PR·커밋·Jira 링크
```

본문은 **맥락 → 선택지(트레이드오프) → 결정 → 결과** 순서입니다.

## 소급 기록

이미 내려진 결정을 나중에 문서로 남길 때의 규칙입니다. 백필은 정의상 사후 재구성이므로 "결정 시점에 알고 있던 사실만 적는다"를 지키려면 별도 표시가 필요합니다.

- 헤더에 `**기록**: 소급 (작성일)`을 표시합니다. `**날짜**`는 **결정 시점**이며, 추정이라면 근거가 되는 커밋·PR을 맥락에 명시합니다.
- 「결정」 절에서 이유의 성격을 아래 넷 중 하나로 밝힙니다.

| 분류 | 쓰는 방식 |
|---|---|
| 능동적 선택 | 대안을 실제로 검토함 → 선택지 표를 정상 작성 |
| 기본값 수용 | 프레임워크·생성기 기본을 그대로 씀 → "바꿀 이유를 찾지 못했다"고 적음 |
| 제약으로 주어짐 | 조직 표준·공용 계약·외부 요건이 정함 → 제약을 명시하고 그 **안에서** 무엇을 골랐는지 씀 |
| 근거 소실 | "당시 판단 근거를 재구성할 수 없다"고 적고 재검토 트리거를 강하게 검 |

**없는 이유를 지어내지 않습니다.** 소급 기록의 가치는 정확성에 있고, 그럴듯한 사후 서사는 기록 전체를 신뢰할 수 없게 만듭니다.

## 목록

| BD | 결정 | 상태 | 관련 |
|---|---|---|---|
| [BD-01](BD-01-h2-removal-testcontainers.md) | 테스트 런타임에서 H2 제거, PostgreSQL Testcontainers로 단일화 | Accepted | back#12 |
| [BD-02](BD-02-base-entity-common-columns.md) | BaseEntity는 created_at·deleted_at만 공유, updated_at 없음 | Accepted | S15P11A705-41 |
| [BD-03](BD-03-api-response-envelope.md) | 성공·오류 응답을 공통 envelope(ApiResponse)로 통일 | Accepted | S15P11A705-53 |
| [BD-04](BD-04-cursor-pagination.md) | 목록은 커서 기반 CursorPage + Base64(정렬키,id) 불투명 커서, size 기본 20·상한 100 | Accepted | S15P11A705-42 |
| [BD-05](BD-05-foundation-reset.md) | 파운데이션 리셋 — 초기 설정을 최신 `dev`에서 작은 PR로 다시 구성 | Accepted | Issue #9 |
| [BD-06](BD-06-context-immutability.md) | Context 불변 — 수정은 삭제+생성. 버전 컬럼 제거, 수정 경합을 삭제 경합에 흡수 | Accepted | S15P11A705-76 |
| [BD-07](BD-07-soft-delete-no-restore.md) | 소프트 삭제 + 복구 없음 + 활성행 부분 유니크 | Accepted | S15P11A705-76 |
| [BD-08](BD-08-no-record-update-path.md) | Record 수정 경로를 두지 않는다 (공용 문서 정정 후속) | Accepted | S15P11A705-76 |
| [BD-09](BD-09-integrity-in-database.md) | 무결성을 앱이 아닌 DB에 — `CHECK`·부분 유니크·서로게이트 키 | Accepted | S15P11A705-76 |
| [BD-10](BD-10-minimum-holding-invariants.md) | 최소 보유 불변식 — 자동 연쇄 대신 409 확인 후 force, 잠금은 부모에 | Accepted | S15P11A705-76 |
| [BD-11](BD-11-duplicate-record-idempotent.md) | Collection 중복 Record 추가는 실패가 아닌 멱등 | Accepted | S15P11A705-76 |
| [BD-12](BD-12-public-boundary-query-dto-split.md) | 공개 경계를 쿼리·DTO 분리로 강제, 403 대신 404 | Accepted | S15P11A705-76 |
| [BD-13](BD-13-identifier-concealment.md) | 식별자 은닉 — `member.id` 비공개, Collection id를 진입점으로 | Accepted | S15P11A705-76 |
| [BD-14](BD-14-shelf-not-a-table.md) | Shelf·Library를 물리 테이블로 두지 않는다 | Accepted | S15P11A705-76 |
| [BD-15](BD-15-ai-derived-immediate-purge.md) | AI 파생 데이터 즉시 파기 + 백엔드의 `ai` 스키마 삭제 예외 | Accepted | S15P11A705-76 |
| [BD-16](BD-16-async-without-message-queue.md) | 비동기 AI를 큐 없이 DB State + Scheduler로 | Accepted | S15P11A705-76 |
| [BD-17](BD-17-keyword-preset-and-visibility.md) | Keyword는 프리셋에서만 · 3등급 공개 · 상위 집계 비저장 | Accepted | S15P11A705-76 |
| [BD-18](BD-18-place-snapshot.md) | Place는 공용 스냅샷 — 갱신·자동 병합 금지, 검색은 프론트가 직접 | Accepted | S15P11A705-76 |
| [BD-19](BD-19-selective-denormalization.md) | 선택적 비정규화 — `record_count`는 두고 팔로워 수는 두지 않는다 | Accepted | S15P11A705-76 |
| [BD-20](BD-20-auth-token-model.md) | 인증 토큰 — JWT Access 30분 / Refresh 7일, Refresh는 Redis | Accepted | S15P11A705-76 |
| [BD-21](BD-21-signup-commit-point.md) | 가입 확정 시점 — 약관 동의 전에는 `member`를 만들지 않는다 | Accepted | S15P11A705-76 |
| [BD-22](BD-22-collection-auto-publish.md) | Collection 자동 발행 + `is_published` 컬럼 유지 | Accepted | S15P11A705-76 |
| [BD-24](BD-24-context-origin-created-at.md) | Context 최초 작성 시각 보존 — `origin_created_at`, 목록은 오래된순 (BD-06 트리거 발동) | Accepted | docs#16 |
