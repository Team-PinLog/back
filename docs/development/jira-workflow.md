# Jira 운영 가이드

> **임시 위치 안내.** 이 문서는 back·front·ai가 동일하게 따르는 **조직 공용** 규칙이라, 최종 권위 위치는 `Team-PinLog/infra`의 `docs/`(거버넌스 문서 계열, `git-governance.md` 옆)입니다. Jira 티켓으로 정식 이관하기 전까지 백엔드 팀이 바로 참고할 수 있도록 여기에 임시로 둡니다. **이관 시 이 파일은 infra 링크로 대체합니다.**

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를, 개발 순서는 [개발 워크플로우](workflow.md)를 따릅니다. 이 문서는 그 흐름에서 **Jira를 어떻게 쓰는가**를 정리합니다.

## 기본 규칙

- 일반 개발 작업의 **작업 단위는 Jira 티켓**입니다. GitHub Issue 연결은 선택입니다.
- 코드를 만지기 전에 티켓을 발급하고 **키를 확보**한 뒤, 브랜치·커밋·PR에 같은 키를 사용합니다.
- 프로젝트 키: **`Jira`**. 티켓 키는 `Jira-<번호>` 형식입니다.

> **예외**: backend foundation reset은 Jira 없이 [Issue #9](https://github.com/Team-PinLog/back/issues/9)로 단독 추적합니다. 이 예외는 일반 작업에 적용하지 않습니다.

## 티켓 발급

Jira에서 직접 만들거나, 자연어로 빠르게 만들고 싶으면 [`Team-PinLog/cowork`](https://github.com/Team-PinLog/cowork)("할 일 올리기")를 사용합니다. cowork는 로그인 사용자의 Jira 계정으로 `Jira` 프로젝트에 **Task**를 생성합니다.

현재 팀 운영 기준(확정):

- **타입**: `Task` 중심으로 운영합니다.
- **담당자**: 작업을 맡는 사람으로 지정합니다.
- **스프린트**: **사용합니다.** 티켓을 만들 때 활성 스프린트에 넣습니다. 비워 두면 보드가 아니라 백로그로 들어가 팀에게 보이지 않습니다.
- **story point·상위(parent)·에픽**: 현재 사용하지 않습니다. 도입하려면 팀 합의 후 이 문서를 갱신합니다.

> **스프린트를 쓰지 않는다고 적혀 있던 것을 2026-07-31에 정정했습니다.** 실제로는 쓰고 있었고, 이 문서를 근거로 스프린트 없이 티켓 5개를 만들어 백로그에 묻힌 사고가 있었습니다. cowork는 스프린트를 지정하지 않으므로 **생성 후 보드로 옮기는 것은 사람 몫**입니다.
>
> `story point`·`상위`·`에픽` 셋은 확인하지 않았습니다. 원래 한 문장에 넷이 묶여 있었는데, 확인한 것만 갈라 고쳤습니다.

한 줄은 기본적으로 하나의 작업으로 봅니다. 완료 항목·일정·메모는 티켓으로 만들지 않습니다.

## 키가 흐르는 곳

확보한 티켓 키는 작업 전체에 일관되게 사용합니다.

| 위치 | 형식 | 예시 |
| --- | --- | --- |
| 브랜치 | `{type}/{jira-key}-{summary}` | `feat/{jira-key}-member-search` |
| 커밋 | `{type}({jira-key}): {summary}` | `feat(Jira 작업): add member search` |
| PR 본문 | "Jira (필수)" 항목에 키/URL | `Jira 작업` |

`type`은 `feat` `fix` `docs` `refactor` `chore` `test` `perf` 중 하나입니다([CONTRIBUTING.md](../../CONTRIBUTING.md)). PR은 [PR 템플릿](../../.github/pull_request_template.md)의 "Jira (필수)" 항목을 채워야 하며, 관련 GitHub Issue는 있을 때만 선택적으로 연결합니다.

## 상태 관리

티켓 상태는 작업 진행에 맞춰 갱신합니다. 최소 흐름은 다음과 같습니다.

```text
To Do  →  In Progress  (PR 생성)
       →  Done         (PR 머지)
```

상태는 **PR 이벤트에 연동**합니다.

- **PR을 생성하면** 티켓을 **진행 중**으로 옮깁니다.
- **PR이 `dev`에 머지되면** 티켓을 **완료**로 옮깁니다([개발 워크플로우](workflow.md)의 "병합 후").
- 이 두 전환은 아래 [상태 자동 전환](#상태-자동-전환-jira-자동화)으로 자동화합니다. 자동화가 아직 없거나 매칭에 실패하면 **수동으로** 위 규칙대로 옮깁니다.
- 세부 상태 컬럼(리뷰 중 등)을 추가로 쓸지는 팀 Jira 보드 설정을 따릅니다.

## 상태 자동 전환 (Jira 자동화)

위 전환(PR 생성 → 진행 중, 머지 → 완료)은 **Jira 네이티브 자동화**로 처리합니다. GitHub에 시크릿·워크플로 코드를 두지 않습니다.

**전제 — GitHub for Jira 연동.** Jira가 PR 이벤트를 받으려면 조직에 GitHub for Jira 연동이 연결돼 있어야 합니다. 이슈 화면 우측 **개발(Development) 패널**이 보이면 연결된 것입니다. 없으면 Jira → **Apps → GitHub for Jira**에서 `Team-PinLog` 저장소를 연결합니다.

**매칭 근거.** 자동화는 브랜치·커밋·PR에 담긴 티켓 키(`Jira-<번호>`)로 이슈를 찾습니다. [키가 흐르는 곳](#키가-흐르는-곳) 규약을 지키면 자동으로 매칭됩니다.

**규칙 2개** (Jira → 프로젝트 `Jira` → **프로젝트 설정 → Automation → 규칙 만들기**):

| # | 트리거(When) | 동작(Then) |
| --- | --- | --- |
| 1 | Pull request created | Transition issue → **진행 중** |
| 2 | Pull request merged | Transition issue → **완료** |

- 트리거는 Jira 자동화 템플릿의 "When a pull request is created / merged"를 사용합니다.
- 대상 상태 이름은 이 프로젝트 기준 **진행 중**, **완료** 입니다(상태 id는 자동화 UI가 이름으로 처리).
- 규칙 활성화는 프로젝트 관리 권한이 있는 사람이 Jira UI에서 수행합니다(도구로 대행하지 않습니다).

**폴백.** 위 자동화가 설정되기 전이나 키 매칭이 안 될 때는 담당자가 상태를 수동으로 옮깁니다.

## 관련 문서

- [개발 워크플로우](workflow.md) — 티켓 발급부터 병합까지 전체 순서
- [CONTRIBUTING.md](../../CONTRIBUTING.md) — 브랜치·커밋·PR 규칙 원본
- [infra/docs/git-governance.md](https://github.com/Team-PinLog/infra/blob/main/docs/git-governance.md) — 조직 Git/PR 표준(이 문서의 최종 이관처와 같은 계열)
