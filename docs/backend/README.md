# PinLog Backend — 백엔드 파트 문서

백엔드 파트의 **설계·결정·구현 기록**입니다. 백엔드 파트 소유이며, [`docs/ai/`](../ai/)(AI 파트 소유)와 대칭 구조입니다. 공용 계약의 단일 원본은 `Team-PinLog/docs`의 `static/` 문서이고, 여기는 Spring 구현 방법만 다룹니다.

## 편집 경계

이 구역은 백엔드 파트가 작성·관리합니다. 구역은 성격에 따라 둘로 나뉩니다.

| 성격 | 구역 | 갱신 방식 |
|---|---|---|
| 살아있는 문서 | [`spec/`](spec/) | 현행과 다르면 제자리 수정, 낡은 내용은 삭제 |
| 기록 문서 | [`decisions/`](decisions/) · [`implements/`](implements/) · [`troubleshooting/`](troubleshooting/) | **삭제하지 않고 상태만 갱신** (회고·복기 목적) |

## 구역

| 구역 | 내용 |
|---|---|
| [`spec/`](spec/) | 설계·구현 명세 — "무엇을 만들 것인가" |
| [`decisions/`](decisions/) | 결정 기록(ADR, BD 번호) — "왜 그렇게 정했나" + 트레이드오프 |
| [`implements/`](implements/) | 구현 리포트(BI 번호) — "어떻게 만들었나" + 검증 결과 |
| [`troubleshooting/`](troubleshooting/) | 문제 해결(BT 번호) — 증상·원인·해결 과정 |
| [`WORKLOG.md`](WORKLOG.md) | 시간순 작업 로그 |

## 번호 체계

`BD-##`(결정) · `BI-##`(구현 리포트) · `BT-##`(트러블슈팅)은 **백엔드 독립 번호**로 1번부터 시작합니다. AI 파트의 P/T/I 번호(전수 인벤토리)와는 별개입니다.

단, back 아티팩트에 **파트 간 영향**이 있는 결정(마이그레이션 번호 구간, 스키마 소유 등)은 기존처럼 [`docs/ai/proposals/`](../ai/proposals/)의 P 번호 절차를 따릅니다. 이 구역은 백엔드 도메인 내부 기록 전용입니다.
