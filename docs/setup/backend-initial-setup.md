# Backend 초기 기반 정리

이 문서는 [backend foundation reset 상위 Issue #9](https://github.com/Team-PinLog/back/issues/9)의 배경, 분리한 변경과 현재 개발 시작 지점을 팀에 공유합니다.

## 왜 다시 정리했는가

기존 [PR #8](https://github.com/Team-PinLog/back/pull/8)은 충돌 상태에서 문서, 패키지 골격, 커버리지 규칙과 로컬 설정을 한 번에 묶었습니다. 그 결과 최신 `dev`에 필요한 변경을 안전하게 검토하거나 병합할 수 없었습니다.

또한 Gradle Wrapper 실행 권한이 없었고, Spring Boot 4.1에서 Flyway가 자동 실행되지 않는 구성이 있었습니다. H2 테스트는 PostgreSQL 전용 migration과 pgvector를 실행하지 않아 성공해도 실제 환경을 보장하지 못했습니다. Compose 이미지·포트·healthcheck·환경 변수도 한 흐름으로 재현되지 않았으며, `dev` PR의 공통 CI 검증도 없었습니다.

따라서 최신 `dev`에서 작은 PR로 기반을 다시 만들고, 자동화할 수 있는 제한은 Gradle·Testcontainers·GitHub Actions·branch protection으로 강제하며, 사람의 판단이 필요한 규칙은 `CONTRIBUTING.md`를 원본으로 정리했습니다.

## 무엇을 바꿨는가

| 구분 | 목적과 범위 | 링크 | 검증 결과 |
| --- | --- | --- | --- |
| 상위 작업 | PostgreSQL 기준의 실행·테스트·CI·팀 개발 계약을 5개 PR로 재구성 | [Issue #9](https://github.com/Team-PinLog/back/issues/9) | foundation reset의 추적 원본 |
| PR 1 | 임시 Security와 OAuth, 고정 계정을 제거하고 Wrapper 실행 권한과 문서 링크를 정리 | [PR #10](https://github.com/Team-PinLog/back/pull/10) | 무인증 요청이 애플리케이션 404로 처리되고 `./gradlew clean check --no-daemon` 성공 |
| PR 2 | PostgreSQL 16 + pgvector와 Redis 7의 이미지·포트·환경 변수·healthcheck를 Compose에 고정 | [PR #11](https://github.com/Team-PinLog/back/pull/11) | `docker compose config`, `docker compose up -d --wait`, 데이터 유지와 초기화 흐름 확인 |
| PR 3 | H2를 제거하고 PostgreSQL Testcontainers와 빈 DB Flyway 검증을 추가 | [PR #12](https://github.com/Team-PinLog/back/pull/12) | V1, V100, V101, V102와 schema, `vector` extension, 이력 테이블 검증 및 전체 check 성공 |
| PR 4 | 동일한 Gradle 검증을 `dev` PR의 GitHub Actions 품질 게이트로 전환 | [PR #13](https://github.com/Team-PinLog/back/pull/13) | `backend-ci / check`, JaCoCo 보고서 업로드, 승인 1건·대화 해결·관리자 적용을 포함한 `dev` 보호 규칙 적용 |
| PR 5 | 사람과 에이전트가 같은 개발 계약을 읽도록 문서, 템플릿과 설정 경계를 정리 | 현재 `docs/backend-development-harness` 브랜치 | 문서 링크, Claude 설정 JSON, Compose config와 전체 Gradle check 성공 |

PR 5는 아직 URL이 없는 현재 브랜치의 변경입니다. 상위 Issue와 이미 병합된 PR의 링크는 위 표의 실제 GitHub URL을 사용합니다.

## 현재 개발 시작 방법

프로젝트 루트에서 다음을 실행합니다.

```bash
cp .env.example .env
docker compose up -d --wait
docker compose ps
./gradlew bootRun
```

Compose가 PostgreSQL과 Redis를 `healthy`로 만들면 애플리케이션은 `http://localhost:8080/api/core`에서 실행됩니다. 작업 완료 전에는 Docker를 실행한 상태로 다음 전체 검증을 수행합니다.

```bash
./gradlew clean check --no-daemon
```

서비스 중지는 `docker compose down`, 로컬 PostgreSQL 데이터까지 초기화할 때만 `docker compose down -v`를 사용합니다. 더 자세한 시작, 검증, PR 흐름은 [CONTRIBUTING.md](../../CONTRIBUTING.md)가 원본입니다.

## 인증 작업 경계

현재 `dev`는 무인증입니다. 일반 API 개발은 Spring Security에 막히지 않아야 하며, H2나 임시 Security 구성을 되살리지 않습니다.

인증 기능은 별도 PR에서 다음을 함께 제공할 때만 추가합니다.

- 필요한 Spring Security와 인증 의존성
- principal과 토큰 또는 세션 계약, 공개·보호 경로 정의
- 성공, 미인증 401, 권한 부족 403 또는 확정된 리소스 은닉 404 테스트
- 로컬 개발 인증 방법과 관련 개발 규칙 문서

## 팀 규칙 위치

| 문서 | 책임 |
| --- | --- |
| [CONTRIBUTING.md](../../CONTRIBUTING.md) | 사람과 모든 도구가 따르는 시작 절차, 작업 추적, 검증과 PR 규칙의 단일 원본 |
| [docs/development/](../development/) | API, 데이터베이스, 테스트의 상세 구현 규칙 |
| [CLAUDE.md](../../CLAUDE.md) | Claude Code가 필요한 문서와 검증을 순서대로 실행하게 하는 짧은 하네스 |
| [AGENTS.md](../../AGENTS.md) | AGENTS 지원 도구를 `CLAUDE.md`와 단일 원본으로 연결 |
