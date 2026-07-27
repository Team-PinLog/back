# 결정 기록 (Decisions, ADR)

백엔드 도메인 내부의 기술 결정을 `BD-##` 번호로 기록합니다 — "왜 그렇게 정했나"와 그때 검토한 트레이드오프. 상태가 **Accepted**인 것은 확정된 결정이며 구현이 따라야 합니다.

파트 간 영향이 있는 결정은 여기가 아니라 [`docs/ai/proposals/`](../../ai/proposals/)의 P 번호 절차를 따릅니다.

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
- **날짜**:
- **관련**: PR·커밋·Jira 링크
```

본문은 **맥락 → 선택지(트레이드오프) → 결정 → 결과** 순서입니다.

## 목록

| BD | 결정 | 상태 | 관련 |
|---|---|---|---|
| [BD-01](BD-01-h2-removal-testcontainers.md) | 테스트 런타임에서 H2 제거, PostgreSQL Testcontainers로 단일화 | Accepted | back#12 |
| [BD-02](BD-02-base-entity-common-columns.md) | BaseEntity는 created_at·deleted_at만 공유, updated_at 없음 | Accepted | S15P11A705-41 |
| [BD-03](BD-03-api-response-envelope.md) | 성공·오류 응답을 공통 envelope(ApiResponse)로 통일 | Accepted | S15P11A705-53 |
| [BD-04](BD-04-cursor-pagination.md) | 목록은 커서 기반 CursorPage + Base64(정렬키,id) 불투명 커서, size 기본 20·상한 100 | Accepted | S15P11A705-42 |
