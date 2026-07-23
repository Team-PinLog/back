# PinLog Backend 문서

루트 [`README.md`](../README.md)가 기술 스택·실행·운영을 다룬다면, 이 디렉터리는 도메인 로직과 파트 간 연동을 **어떻게 구현하는지**를 다룹니다.

## 시작과 팀 규칙

- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — 시작 절차, Jira 중심 작업 추적, 검증과 PR 규칙의 단일 원본
- [`setup/backend-initial-setup.md`](setup/backend-initial-setup.md) — backend foundation reset의 배경과 현재 개발 시작 방법
- [`development/`](development/) — [API](development/api-conventions.md), [데이터베이스](development/database-conventions.md), [테스트](development/testing-conventions.md) 상세 규약

## AI 파트 문서

[`ai/`](ai/) — AI 연동·Feed 관련 **설계·결정·구현 기록** (AI 파트 소유). 하위 구조·문서는 AI 파트가 관리합니다.

### 현재 구현 기준

백엔드 도메인 작업은 아래의 현재 구현 기준을 따릅니다.

- [데이터베이스 개발 규약](development/database-conventions.md) — Flyway의 현재 기준: `V1` 공통 기반, `V2`~`V99` 백엔드, `V100`~`V199` AI, `V200` 이후 미할당.
- [`core.feed_event` 소유](ai/proposals/P22-feed-event-ownership.md) — `V102`(AI 구간)에 정의됨. **백엔드 `V2~`에서 중복 정의 금지**(재정의 시 `already exists`).

### 과거 제안·결정 기록

- [P21: Flyway 마이그레이션 파트별 번호 구간](ai/proposals/P21-flyway-migration-convention.md) — 과거 결정 기록입니다. 현재 구현 기준으로 사용하지 말고 위 데이터베이스 개발 규약을 따릅니다.

## 백엔드 공통 문서

백엔드 공통 규약은 [`development/`](development/)에서 관리합니다. 도메인별 설계 문서는 해당 도메인 디렉터리에 추가하고, 시작·검증·PR 규칙은 중복하지 않고 [`CONTRIBUTING.md`](../CONTRIBUTING.md)를 참조합니다.

## 공용 계약 참조

공용 계약(원칙·상태값·내부 API·Visibility·AI 데이터 구조)의 단일 원본은 `Team-PinLog/docs`의 `static/05_AI_설계.md`입니다. 이 저장소 문서와 충돌하면 공용 계약이 우선합니다.
