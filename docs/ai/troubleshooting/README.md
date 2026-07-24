# 트러블슈팅 (Troubleshooting)

구현·문서 작업 중 겪은 문제와 그 해결을 재현 가능한 형태로 남깁니다.

## 보존 원칙

이 폴더는 문제 해결 과정과 구현 이력을 기록합니다. **해결·완료된 항목도 삭제하지 않고 상태 표시만 갱신합니다.** 회고와 복기에서 "무엇을 어떻게 해결했는가"를 추적하기 위함입니다.

- 해결됨 → 문서 유지 + `상태: 해결됨` + 해결 경로·링크 추가
- 무효화 → 문서 유지 + `상태: 무효(사유)` 표기
- 삭제 → 하지 않음. 잘못 작성된 문서도 정정으로 처리

※ `spec/`은 현재 유효한 명세이므로 이 원칙의 대상이 아닙니다(낡은 내용은 갱신·삭제).

## 목록

| T | 증상 | 상태 | 문서/해결 |
|---|---|---|---|
| T9 | H2가 pgvector·`VECTOR` 미지원 → 마이그레이션 검증 불가 | ✅ 해결됨 | [h2-pgvector-incompat.md](h2-pgvector-incompat.md) — H2 제거 + Testcontainers 전환(back#12) |
| T10 | `flyway_schema_history` 스키마 배치 함정(`flyway.schemas` 지정 시 core에 섞이고 V1과 충돌) | ✅ 해결됨 | [P24](../proposals/P24-flyway-schemas-unspecified.md) — schemas 미지정 → 이력 public |
