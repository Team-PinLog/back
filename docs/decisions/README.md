# 결정 기록 (ADR)

백엔드 구현에서 내린 설계 결정을 기록합니다. 각 문서는 **무엇을 왜 정했고 무엇을 버렸는지**를 남겨, 이후 합류자와 리뷰어가 배경을 재구성할 수 있게 합니다.

공용 계약(파트 간 규약)은 `Team-PinLog/docs`가 단일 원본입니다. 여기에는 그 계약을 백엔드에서 구현하며 내린 **백엔드 국한 결정**만 둡니다.

## 형식

각 ADR은 다음 골격을 따릅니다.

```text
# ADR-NNN: 제목
- 상태 · 날짜 · 관련 PR/커밋 · 소유 파트
## 맥락   무엇이 문제였나
## 결정   무엇으로 정했나
## 근거   왜
## 버린 대안   무엇을, 왜 버렸나
## 영향   파트 간 계약·구현에 미치는 것
## 검증   어떻게 확인했나
```

## 목록

| ADR | 제목 | 상태 |
|---|---|---|
| [ADR-001](ADR-001-flyway-version-convention.md) | Flyway 마이그레이션 파트별 번호 구간 | 채택 |
| [ADR-002](ADR-002-feed-event-ownership.md) | `core.feed_event`를 AI 구간(V102)에 배치 | 채택 |
| [ADR-003](ADR-003-flyway-schemas-unspecified.md) | `flyway.schemas` 미지정 — 이력 테이블을 public에 | 채택 |
