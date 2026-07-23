# PinLog Backend 문서

루트 [`README.md`](../README.md)가 기술 스택·실행·운영을 다룬다면, 이 디렉터리는 도메인 로직과 파트 간 연동을 **어떻게 구현하는지**를 다룹니다.

## AI 파트 문서

[`ai/`](ai/) — AI 연동·Feed 관련 **설계·결정·구현 기록** (AI 파트 소유). 하위 구조·문서는 AI 파트가 관리합니다.

### 백엔드가 참고해야 할 확정 규칙

아래는 백엔드 도메인 작업 시 반드시 따라야 하는, AI 파트가 확정한 규칙입니다.

- [Flyway 마이그레이션 버전 구간](ai/proposals/P21-flyway-migration-convention.md) — **`V2`~`V99`가 백엔드 구간**. 자기 구간 밖 번호 미사용.
- [`core.feed_event` 소유](ai/proposals/P22-feed-event-ownership.md) — `V102`(AI 구간)에 정의됨. **백엔드 `V2~`에서 중복 정의 금지**(재정의 시 `already exists`).

## 백엔드 공통 문서

도메인·인증·API 규약 등 백엔드 소관 문서는 백엔드가 이 `docs/` 아래에 자체 구조로 추가합니다. (현재 미작성)

## 공용 계약 참조

공용 계약(원칙·상태값·내부 API·Visibility·AI 데이터 구조)의 단일 원본은 `Team-PinLog/docs`의 `static/05_AI_설계.md`입니다. 이 저장소 문서와 충돌하면 공용 계약이 우선합니다.
