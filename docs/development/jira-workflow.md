# Jira 운영 가이드

> **임시 위치 안내.** 이 문서는 back·front·ai가 동일하게 따르는 **조직 공용** 규칙이라, 최종 권위 위치는 `Team-PinLog/infra`의 `docs/`(거버넌스 문서 계열, `git-governance.md` 옆)입니다. Jira 티켓으로 정식 이관하기 전까지 백엔드 팀이 바로 참고할 수 있도록 여기에 임시로 둡니다. **이관 시 이 파일은 infra 링크로 대체합니다.**

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를, 개발 순서는 [개발 워크플로우](workflow.md)를 따릅니다. 이 문서는 그 흐름에서 **Jira를 어떻게 쓰는가**를 정리합니다.

## 기본 규칙

- 일반 개발 작업의 **작업 단위는 Jira 티켓**입니다. GitHub Issue 연결은 선택입니다.
- 코드를 만지기 전에 티켓을 발급하고 **키를 확보**한 뒤, 브랜치·커밋·PR에 같은 키를 사용합니다.
- 프로젝트 키: **`S15P11A705`**. 티켓 키는 `S15P11A705-<번호>` 형식입니다.

> **예외**: backend foundation reset은 Jira 없이 [Issue #9](https://github.com/Team-PinLog/back/issues/9)로 단독 추적합니다. 이 예외는 일반 작업에 적용하지 않습니다.

## 티켓 발급

Jira에서 직접 만들거나, 자연어로 빠르게 만들고 싶으면 [`Team-PinLog/cowork`](https://github.com/Team-PinLog/cowork)("할 일 올리기")를 사용합니다. cowork는 로그인 사용자의 Jira 계정으로 `S15P11A705` 프로젝트에 **Task**를 생성합니다.

현재 팀 운영 기준(확정):

- **타입**: `Task` 중심으로 운영합니다.
- **담당자**: 작업을 맡는 사람으로 지정합니다.
- **story point·스프린트·상위(parent)·에픽**: 현재 사용하지 않습니다. 도입하려면 팀 합의 후 이 문서를 갱신합니다.

한 줄은 기본적으로 하나의 작업으로 봅니다. 완료 항목·일정·메모는 티켓으로 만들지 않습니다.

## 키가 흐르는 곳

확보한 티켓 키는 작업 전체에 일관되게 사용합니다.

| 위치 | 형식 | 예시 |
| --- | --- | --- |
| 브랜치 | `{type}/{jira-key}-{summary}` | `feat/S15P11A705-14-member-search` |
| 커밋 | `{type}({jira-key}): {summary}` | `feat(S15P11A705-14): add member search` |
| PR 본문 | "Jira (필수)" 항목에 키/URL | `S15P11A705-14` |

`type`은 `feat` `fix` `docs` `refactor` `chore` `test` `perf` 중 하나입니다([CONTRIBUTING.md](../../CONTRIBUTING.md)). PR은 [PR 템플릿](../../.github/pull_request_template.md)의 "Jira (필수)" 항목을 채워야 하며, 관련 GitHub Issue는 있을 때만 선택적으로 연결합니다.

## 상태 관리

티켓 상태는 작업 진행에 맞춰 갱신합니다. 최소 흐름은 다음과 같습니다.

```text
To Do  →  In Progress  (브랜치 생성·개발 시작)
       →  Done         (PR 병합, 완료 조건 충족)
```

- 개발을 시작하면 티켓을 **진행 중**으로 옮깁니다.
- PR이 병합되고 완료 조건을 충족하면 티켓을 **완료**로 옮깁니다([개발 워크플로우](workflow.md)의 "병합 후").
- 세부 상태 컬럼(리뷰 중 등)을 추가로 쓸지는 팀 Jira 보드 설정을 따릅니다.

## 관련 문서

- [개발 워크플로우](workflow.md) — 티켓 발급부터 병합까지 전체 순서
- [CONTRIBUTING.md](../../CONTRIBUTING.md) — 브랜치·커밋·PR 규칙 원본
- [infra/docs/git-governance.md](https://github.com/Team-PinLog/infra/blob/main/docs/git-governance.md) — 조직 Git/PR 표준(이 문서의 최종 이관처와 같은 계열)
