# 개발 워크플로우

시작 절차와 규칙의 단일 원본은 [CONTRIBUTING.md](../../CONTRIBUTING.md)입니다. 이 문서는 **기능 하나를 시작해서 `dev`에 병합하기까지의 실제 순서**를 한 페이지로 정리합니다. Git·PR·머지의 조직 표준은 [infra/docs/git-governance.md](https://github.com/Team-PinLog/infra/blob/main/docs/git-governance.md)를 따르며, 여기서는 백엔드에 필요한 구체 단계(Jira, TDD, Testcontainers, Flyway, `gradle check`)를 함께 엮습니다.

## 전체 흐름 (한눈에)

```text
Jira 티켓 발급
  → 기능 브랜치 생성        ({type}/{jira-key}-{summary})
  → 실패 테스트 먼저 (RED)   PostgreSQL Testcontainers
  → 구현 (GREEN)
  → 로컬 검증               ./gradlew clean check --no-daemon
  → 커밋                    {type}({jira-key}): {summary}
  → push → PR              본문에 Jira 키 + RED/GREEN/Regression 증거
  → backend-ci / check + 승인 1건 + 대화 해결
  → squash 병합 + 브랜치 자동삭제
  → 병합 후: dev 최신화, 티켓 상태 갱신
```

각 단계의 근거 규칙은 아래에서 설명합니다.

## 0. 시작 전 — Jira 티켓을 먼저 만든다

일반 개발 작업은 **Jira가 작업 단위**입니다. 코드를 만지기 전에 티켓을 발급하고 키(`S15P11A705-123` 형식)를 확보합니다. 자연어로 티켓만 빠르게 만들고 싶으면 [`Team-PinLog/cowork`](https://github.com/Team-PinLog/cowork)("할 일 올리기")를 사용합니다.

> **예외**: 이번 backend foundation reset은 Jira 없이 [Issue #9](https://github.com/Team-PinLog/back/issues/9)로 단독 추적합니다. 이 예외는 일반 작업에 적용하지 않습니다.

## 1. 기능 브랜치를 만든다

항상 최신 `dev`에서 분기합니다. `dev`에는 직접 push하지 않습니다.

```bash
git fetch origin dev
git switch dev
git merge --ff-only origin/dev
git switch -c feat/S15P11A705-123-member-search
```

브랜치 이름은 `{type}/{jira-key}-{summary}`, `type`은 `feat` `fix` `docs` `refactor` `chore` `test` `perf` 중 하나입니다.

## 2. 실패하는 테스트를 먼저 쓴다 (RED)

구현보다 **테스트가 먼저**입니다. 요구사항을 증명하는 테스트를 작성하고, 아직 구현이 없어 **실패**하는 것을 확인합니다. 세부는 [테스트 규약](testing-conventions.md)을 따릅니다.

- 순수 단위 테스트는 Spring Context를 올리지 않습니다.
- DB가 필요한 테스트는 **PostgreSQL Testcontainers**를 사용합니다(H2 금지). `PostgresContainerSupport`를 상속합니다.
- 새 클래스는 [패키지 구조 규약](package-structure.md)에 정의된 위치에만 만듭니다.

```bash
# 실패(RED) 확인
./gradlew test --no-daemon
```

## 3. 구현한다 (GREEN)

테스트가 통과할 만큼만 구현합니다.

- Entity를 요청/응답으로 직접 노출하지 않습니다. DTO로 분리합니다([API 규약](api-conventions.md)).
- 스키마 변경은 Hibernate 자동 생성이 아니라 **Flyway 마이그레이션**으로 합니다. 자기 소유 버전 구간만 사용합니다([데이터베이스 규약](database-conventions.md)).

## 4. 로컬에서 전체 검증한다

완료를 보고하기 전 **반드시** 실행합니다. Testcontainers가 뜨므로 Docker가 실행 중이어야 합니다.

```bash
./gradlew clean check --no-daemon
```

Docker가 없다고 DB 테스트를 건너뛰지 않습니다. Docker를 켜고 다시 실행합니다. 변경 유형별 추가 필수 테스트는 [테스트 규약](testing-conventions.md)의 표를 따릅니다.

## 5. 커밋한다

커밋 메시지는 `{type}({jira-key}): {summary}` 형식입니다.

```bash
git add <파일>
git commit -m "feat(S15P11A705-123): add member search"
```

## 6. push하고 PR을 연다

```bash
git push -u origin HEAD
gh pr create --base dev --head "$(git branch --show-current)"
```

PR 본문에는 [PR 템플릿](../../.github/pull_request_template.md)에 따라 다음을 남깁니다.

- **Jira 키**(필수)와 링크. 관련 GitHub Issue는 선택.
- **검증 증거** — 실행한 명령과 결과:
  - RED: 변경 전 실패(non-zero exit)
  - GREEN: 목표 테스트 성공(exit 0)
  - Regression: `./gradlew clean check --no-daemon` 전체 성공(exit 0)
- 범위 밖 항목과 리뷰가 필요한 판단.

## 7. CI와 리뷰를 통과한다

`dev`는 보호되어 있어 다음을 모두 만족해야 병합됩니다(관리자 포함).

- `backend-ci / check` 성공 (최신 `dev` 기준)
- **승인 1건** — 작성자는 자기 PR을 승인할 수 없습니다.
- 미해결 리뷰 **대화 해결**

브랜치가 최신 `dev`보다 뒤처지면 로컬에서 rebase로 따라잡고 다시 push합니다(공용 브랜치 히스토리를 깔끔하게 유지).

```bash
git fetch origin dev
git rebase origin/dev
git push --force-with-lease
```

## 8. squash로 병합한다

PinLog 표준 병합은 **squash**입니다. PR 하나가 `dev`에 커밋 하나로 남고, 병합 후 기능 브랜치는 자동 삭제됩니다.

```bash
HEAD_SHA=$(gh pr view <번호> --json headRefOid --jq .headRefOid)
gh pr merge <번호> --auto --squash --delete-branch \
  --match-head-commit "$HEAD_SHA"
```

## 9. 병합 후

- 로컬 `dev`를 최신화합니다.

  ```bash
  git switch dev
  git fetch origin dev
  git merge --ff-only origin/dev
  ```

- 병합된 로컬 브랜치를 정리합니다(원격은 자동 삭제됨).
- **Jira 티켓 상태를 갱신**하고, 완료 조건을 충족했으면 닫습니다.

## 요약 규칙

| 단계 | 필수 |
| --- | --- |
| 시작 | Jira 티켓 키 확보 (foundation reset은 Issue #9 예외) |
| 브랜치 | `{type}/{jira-key}-{summary}`, 최신 `dev`에서 분기 |
| 개발 | 실패 테스트 먼저(RED) → 구현(GREEN), DB는 Testcontainers |
| 검증 | `./gradlew clean check --no-daemon` (Docker 필요) |
| 커밋 | `{type}({jira-key}): {summary}` |
| PR | Jira 키 + RED/GREEN/Regression 증거 |
| 병합 | CI + 승인 1 + 대화 해결 → squash + 브랜치 삭제 |
