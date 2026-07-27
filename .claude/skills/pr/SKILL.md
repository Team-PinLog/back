---
name: pr
description: 백엔드 PR 생성 절차. 작업을 마치고 dev 대상 PR을 올릴 때 사용 — 브랜치·커밋 형식 검사, 셀프 리뷰, Jira 키 + RED/GREEN/Regression 증거를 갖춘 PR 본문 작성까지.
---

# PR 생성 절차

`docs/development/workflow.md`(6~7단계), `docs/development/jira-workflow.md`, `docs/development/code-review.md`의 작성자 체크리스트를 실행 순서로 엮은 절차다. 각 단계에서 조건이 어긋나면 **멈추고 사용자에게 보고**한다. 임의로 형식을 바꾸지 않는다.

## 1. 전제 확인

1. Jira 키 확보: 브랜치 이름에서 `S15P11A705-<번호>`를 추출한다. 브랜치가 `{type}/{jira-key}-{summary}` 형식(`type`: feat|fix|docs|refactor|chore|test|perf)이 아니거나 키가 없으면 멈추고 사용자에게 키를 확인한다.
2. `dev`·`main`에서 직접 PR을 만들지 않는다. 현재 브랜치가 `dev`/`main`이면 멈춘다.
3. 비밀값·개인정보가 diff에 없는지 확인한다: `git diff origin/dev...HEAD`에서 토큰·비밀번호·자격증명 패턴을 훑는다.

## 2. 검증 증거 수집 (RED/GREEN/Regression)

PR 본문에 넣을 증거를 이 세션의 실제 실행 기록에서 모은다. 없으면 지금 실행한다.

- **RED**: 변경 전 실패했던 테스트와 그 결과(non-zero exit). 이 세션에서 TDD로 진행했다면 그 기록을 쓴다.
- **GREEN**: 목표 테스트 성공 명령과 결과.
- **Regression**: `./gradlew clean check --no-daemon` 전체 성공. 이 세션에서 마지막 소스 변경 이후 실행한 적이 없으면 **지금 실행**한다 (Docker 필요).

Regression이 실패하면 PR을 만들지 않고 실패 내용을 보고한다.

## 3. 셀프 리뷰

원본은 [`docs/development/code-review.md`](../../../docs/development/code-review.md)의 "작성자 — 리뷰 요청 전에"다. 아래는 그 항목을 백엔드 규약으로 구체화한 것이며, **원본이 바뀌면 이 목록도 함께 고친다.**

- [ ] 변경 의도를 증명하는 테스트가 있다(실패 → 통과 확인)
- [ ] **변경 유형별 추가 테스트**를 만족한다 — DB 변경 시 PostgreSQL 통합 테스트, migration 변경 시 빈 DB migration 테스트, API 계약 변경 시 관련 문서 갱신 ([CONTRIBUTING.md](../../../CONTRIBUTING.md)의 검증 표)
- [ ] DB 테스트는 PostgreSQL Testcontainers를 쓴다 (H2 금지)
- [ ] Entity를 request/response로 직접 노출하지 않았다
- [ ] 오류 응답이 `code`/`message`/`traceId` 계약을 지킨다 (해당 시)
- [ ] Flyway는 백엔드 소유 구간(V2~V99)만 썼고, 적용된 마이그레이션을 수정하지 않았다 (해당 시)
- [ ] 새 클래스가 package-structure.md에 정의된 위치에 있다
- [ ] PR이 리뷰 가능한 크기다 — 크면 나누자고 사용자에게 제안

어긋난 항목이 있으면 PR 생성 전에 보고한다.

## 4. 커밋과 push

1. 커밋 메시지: `{type}({jira-key}): {summary}` — 예: `feat(S15P11A705-123): add member search`
2. 브랜치가 최신 `dev`보다 뒤처졌으면 rebase로 따라잡는다 ([workflow.md 7단계](../../../docs/development/workflow.md)).

   ```bash
   git fetch origin dev
   git rebase origin/dev
   ```

   충돌이 나면 임의로 해결하지 말고 멈추고 사용자에게 보고한다. rebase 후 push는 `git push --force-with-lease`다.
3. `git push -u origin HEAD`

## 5. PR 생성

[`.github/pull_request_template.md`](../../../.github/pull_request_template.md) **구조를 그대로** 따라 본문을 작성한다. 템플릿에 있는 항목을 임의로 빼지 않는다.

- 제목: `{type}({jira-key}): {summary}` (커밋과 동일 형식)
- base는 **dev**: `gh pr create --base dev --head "$(git branch --show-current)"`
- 본문 항목:
  - **요약**: 무엇을, 왜 (1~3줄)
  - **Jira (필수)**: 키 또는 URL
  - **관련 GitHub Issue (선택)**: 있을 때만
  - **변경 사항**: 항목별
  - **테스트 / 검증**: 템플릿의 체크박스 4개(`clean check` / DB 변경 시 통합 테스트 / migration 변경 시 빈 DB 테스트 / API 계약 변경 시 문서 갱신)를 해당 항목만 남겨 체크하고, 2단계에서 모은 RED/GREEN/Regression의 **실행 명령과 결과를 그대로** 덧붙인다
  - **배경 · 리뷰 포인트 · 미결/후속**: 템플릿 주석대로 필요할 때만 (단순 feat/fix면 생략)

## 6. 생성 후 안내

- PR URL을 사용자에게 전달한다.
- 병합 조건을 상기시킨다: `backend-ci / check` 성공 + 승인 1건(본인 승인 불가) + 대화 해결, 병합은 **squash + 브랜치 삭제**.
- Jira 상태(To Do → 진행 중)는 PR 생성 이벤트를 받는 Jira 자동화가 옮긴다. 이슈가 그대로면 자동화가 아직 없거나 키 매칭에 실패한 것이므로 **수동 전환이 필요하다고 알린다** ([jira-workflow.md](../../../docs/development/jira-workflow.md)).
- 병합은 사용자가 결정한다 — 이 스킬에서 자동으로 병합하지 않는다.
