# 구현 리포트 (Implements)

무엇을 만들었고 어떻게 검증했는지 기록합니다. `spec/`이 "무엇을 만들 것인가"라면, 여기는 "어떻게 만들었나"와 검증 결과입니다.

## 보존 원칙

이 폴더는 구현 이력을 기록합니다. **완료된 항목도 삭제하지 않고 상태 표시만 갱신합니다.** 회고·복기에서 "무엇을 어떻게 만들었는가"를 추적하기 위함입니다.

- 완료 → 문서 유지 + `상태: 완료`
- 무효화 → 문서 유지 + `상태: 무효(사유)` 표기
- 삭제 → 하지 않음. 잘못 작성된 문서도 정정으로 처리

※ `spec/`은 현재 유효한 명세이므로 이 원칙의 대상이 아닙니다(낡은 내용은 갱신·삭제).

## 목록

| I | 산출 | 상태 | 문서 |
|---|---|---|---|
| I6 | Spring AI 연동·Feed 추천 구현 명세 (back#1) | ✅ 완료 | [spec/](../spec/) (ai-*, feed-*) |
| I7 | back docs README 백엔드 허브화 (back#2) | ✅ 완료 | [../README.md](../README.md) |
| I8 | Flyway 도입 + ai 스키마·feed_event 마이그레이션 V1/V100~102 (back#3) | ✅ 완료 | [2026-07-23-flyway-ai-schema-migration.md](2026-07-23-flyway-ai-schema-migration.md) |
| I15 | 백엔드 작업기록 신설 + 문서 재구조화(spec/proposals/implements/troubleshooting) (back#6) | ✅ 완료 | 이 트리 전체 |
| I16 | Feed `keywords`를 `code`에서 `display_name`으로 교체, 점수 계산 키는 `code` 유지, N+1 쿼리 카운터 신설 (back#146, S15P11A705-252) | ✅ 완료 | [2026-08-03-feed-keyword-display-name.md](2026-08-03-feed-keyword-display-name.md) |
