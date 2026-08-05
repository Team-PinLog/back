<!--
제목: <type>(<JIRA-KEY>): <간결한 설명>
예) feat(S15P11A705-14): 개인 검색 API 추가
type: feat | fix | docs | refactor | chore | test | perf
Jira 키는 필수입니다. 관련 GitHub Issue는 있을 때만 적되, 적을 때는 `Closes #N` 형식을 씁니다.
-->

## 요약
<!-- 이 PR이 무엇을, 왜 하는지 1~3줄. -->

## Jira (필수)
- 키 또는 URL:

## 관련 GitHub Issue (선택)
<!-- 링크만 걸면 머지해도 이슈가 닫히지 않습니다. 여러 개면 줄을 나눠 각각 씁니다. -->
- Closes #

## 변경 사항
-

## 테스트 / 검증
<!-- 재현 가능한 명령과 결과. 없으면 삭제. -->
- [ ] `./gradlew clean check --no-daemon`
- [ ] DB 변경 시 PostgreSQL 통합 테스트
- [ ] migration 변경 시 빈 DB migration 테스트
- [ ] API 계약 변경 시 관련 문서 갱신
- [ ] 되돌리기 어려운 결정을 포함하면 `docs/backend/decisions/`에 BD 추가 또는 기존 BD 링크

<!--
아래는 필요할 때만 (리팩토링·복잡한 결정 등). 단순 feat/fix면 지워도 됩니다.

## 배경
- 왜 이 변경이 필요한가, 대안 대비 이유

## 리뷰 포인트
1. 집중해서 봐야 할 지점 / 판단이 필요한 트레이드오프

## 미결 / 후속
- 이 PR에서 다루지 않은 것
-->
