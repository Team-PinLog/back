# PinLog Backend 기여 가이드

이 문서는 PinLog Backend **고유** 개발 계약의 단일 원본입니다. 에이전트도 이 규칙을 따릅니다. API, 데이터베이스, 테스트, 패키지 구조의 세부 규칙은 [docs/development](docs/development/)에서 확인합니다.

모든 서비스 레포에 공통으로 적용되는 조직 표준(Git/PR/머지 규칙, 배포·런타임 계약)은 이 레포가 복제하지 않고 `infra` 레포를 권위 문서로 참조합니다. 아래 "조직 표준"을 먼저 확인하세요.

## 조직 표준 (권위 문서)

back·front·ai가 동일하게 따르는 규칙은 `Team-PinLog/infra`가 원본입니다. 내용이 이 문서와 어긋나면 infra 문서를 기준으로 하고, 이 문서를 함께 고칩니다.

- **Git / PR / 머지 규칙** — [infra/docs/git-governance.md](https://github.com/Team-PinLog/infra/blob/main/docs/git-governance.md)
  - 병합은 **squash merge**, 머지 후 기능 브랜치 **자동 삭제**
  - PR 본문에 **Jira 키 + TDD 증거(RED/GREEN/Regression)** 필요, `main`/`dev` 직접 push 금지
  - 외부 GitHub Action은 **full commit SHA**로 고정
- **배포 · 런타임 계약** — [infra/docs/backend-conventions.md](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)
  - 서비스 `context-path`는 `/api/core` (컨트롤러 매핑에 prefix 중복 금지)
  - 컨테이너 **UID 1000 non-root**, actuator 필수
  - 이미지 태그 = **커밋 SHA**, GHCR + Argo CD GitOps로 배포

이 문서(및 `docs/development/`)는 위 조직 표준을 전제로, **back 고유 규칙**(패키지 구조, Flyway 버전 구간, Testcontainers 등)만 다룹니다. 자연어로 Jira 티켓만 만들고 싶을 때는 별도 도구 `Team-PinLog/cowork`("할 일 올리기")를 사용합니다.

## 시작 전 준비

- JDK 21
- Docker Desktop 또는 Docker Engine과 Docker Compose
- 저장소에 포함된 Gradle Wrapper를 실행할 수 있는 셸

## 로컬 시작

프로젝트 루트에서 다음 순서로 실행합니다.

```bash
cp .env.example .env
docker compose up -d --wait
docker compose ps
./gradlew bootRun
```

Compose가 PostgreSQL과 Redis가 `healthy` 상태가 될 때까지 기다립니다. 애플리케이션은 `http://localhost:8080/api/core`에서 시작합니다. 컨트롤러 매핑에는 이 context path를 다시 쓰지 않습니다.

서비스만 중지할 때는 `docker compose down`을 사용합니다. PostgreSQL 로컬 데이터를 함께 초기화해야 할 때만 `docker compose down -v`를 사용합니다.

## 작업 추적과 Git 규칙

일반 개발 작업에는 Jira가 필수이고 GitHub Issue 연결은 선택입니다. 작업을 시작하기 전에 Jira 키를 준비하고, 브랜치와 커밋에 같은 키를 사용합니다. Jira 운영 세부는 [Jira 운영 가이드](docs/development/jira-workflow.md)를 따릅니다(조직 공용 규칙으로, 추후 infra로 이관 예정).

```text
브랜치: {type}/{jira-key}-{summary}
커밋: {type}({jira-key}): {summary}
```

예를 들어 `feat/S15P11A705-14-member-search` 브랜치의 커밋은 `feat(S15P11A705-14): add member search`와 같이 작성합니다. `type`에는 `feat`, `fix`, `docs`, `refactor`, `chore`, `test`, `perf`를 사용합니다.

이번 backend foundation reset의 모든 작업은 Jira 없이 [GitHub Issue #9](https://github.com/Team-PinLog/back/issues/9)로 단독 추적하는 예외입니다. 이 예외는 foundation 작업의 브랜치 이름, 커밋 메시지와 PR에도 적용됩니다. 일반 작업에는 적용하지 않으며, 일반 작업은 Jira 키를 계속 사용합니다.

`dev`에는 직접 push하지 않습니다. 변경은 PR로 제출하고, `backend-ci / check`, 승인 1건, 대화 해결을 포함한 저장소 보호 규칙을 통과해야 합니다. 이 보호 규칙은 관리자에게도 적용됩니다.

## 구현 전과 구현 중 규칙

- 기존 코드와 테스트를 먼저 읽고, 변경 의도를 검증하는 실패 테스트를 먼저 작성하거나 갱신합니다.
- 사용하지 않는 빈 패키지, `.gitkeep`, 추측성 도메인 계층을 만들지 않습니다. 패키지는 [패키지 구조 규약](docs/development/package-structure.md)에 정의된 위치에 클래스가 생길 때 만듭니다.
- H2를 추가하지 않습니다. DB 의존 테스트에는 PostgreSQL Testcontainers를 사용합니다.
- 인증 기능은 별도 인증 PR에서 의존성, 인증 계약, 보안 설정, 로컬 개발 방법과 테스트를 함께 제공할 때만 추가합니다. 그 PR이 만족해야 할 계약은 [인증 PR 계약](docs/development/authentication.md)에 있습니다.

기능 하나를 시작해서 병합하기까지의 전체 순서는 [개발 워크플로우](docs/development/workflow.md)에, 리뷰·병합 기준은 [코드 리뷰 가이드](docs/development/code-review.md)에 있습니다. 세부 기준은 [패키지 구조 규약](docs/development/package-structure.md), [API 규약](docs/development/api-conventions.md), [에러 처리 규약](docs/development/error-handling.md), [로깅 규약](docs/development/logging.md), [설정·프로파일 규약](docs/development/configuration.md), [데이터베이스 규약](docs/development/database-conventions.md), [테스트 규약](docs/development/testing-conventions.md)을 따릅니다.

## 검증과 PR

완료를 보고하기 전에는 반드시 다음 명령을 실행합니다.

```bash
./gradlew clean check --no-daemon
```

이 검증은 PostgreSQL Testcontainers를 실행하므로 Docker가 실행 중이어야 합니다. Docker가 실행되지 않은 경우 DB 테스트를 건너뛰지 말고 Docker를 시작한 뒤 다시 실행합니다.

변경 유형별 추가 검증은 다음과 같습니다.

| 변경 | 추가 필수 검증 |
| --- | --- |
| DB 접근 코드 | PostgreSQL 통합 테스트 |
| Flyway migration | 빈 PostgreSQL DB에서 migration 검증 |
| API 계약 | 요청·응답 및 validation 계약 테스트와 관련 문서 갱신 |
| 인증·인가 | 성공, 401, 403 또는 확정된 리소스 은닉 404 테스트 |

일반 PR은 [PR 템플릿](.github/pull_request_template.md)을 사용하고 Jira 키를 필수로 적으며, 관련 GitHub Issue가 있을 때만 선택적으로 연결합니다. foundation reset PR은 Issue #9를 참조하고 Jira 키 없이 제출할 수 있지만, 이 예외 때문에 일반 템플릿의 Jira 필수 규칙은 바뀌지 않습니다. 모든 PR에는 검증 명령과 결과, 범위 밖 항목, 리뷰가 필요한 판단을 남깁니다.

## 문서의 역할

- [README.md](README.md): 기술 스택, 인프라와 운영 참고를 빠르게 안내합니다.
- [docs/setup/backend-initial-setup.md](docs/setup/backend-initial-setup.md): 이번 backend foundation reset의 배경과 결과를 공유합니다.
- [docs/development/](docs/development/): API, DB, 테스트의 상세 개발 규칙을 관리합니다.
- [CLAUDE.md](CLAUDE.md): Claude Code가 이 문서와 상세 규칙을 실행 순서대로 읽게 하는 짧은 하네스입니다.
- [AGENTS.md](AGENTS.md): AGENTS 지원 도구를 `CLAUDE.md`와 이 문서로 연결합니다.

## Claude 설정 경계

저장소 공통 보호 설정은 [`.claude/settings.json`](.claude/settings.json)에만 둡니다. 개인별 권한과 환경 설정은 Git에서 제외되는 `.claude/settings.local.json`에 두고, 필요하면 [예시 파일](.claude/settings.local.json.example)을 복사해 시작합니다. 개인 설정 파일을 커밋하거나 팀 설정에 개인 권한을 추가하지 않습니다.
