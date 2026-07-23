# Backend Foundation Reset Design

## 목적

PinLog 백엔드의 초기 설정을 최신 `dev`에서 작은 PR로 다시 구성한다. 실제로 자동화할 수 있는 제한은 Gradle, 테스트, GitHub Actions와 브랜치 보호로 강제하고, 사람의 판단이 필요한 개발 규칙은 팀 문서와 저장소 템플릿으로 통일한다.

이번 정리는 충돌 상태인 PR #8을 확장하지 않는다. PR #8은 대체되었음을 명시하고 닫은 뒤, 필요한 변경만 최신 `dev`에서 다시 구현한다.

## 확정 결정

- 이번 기반 정리는 Jira를 사용하지 않고 하나의 GitHub 상위 Issue로 관리한다.
- 각 PR은 상위 Issue를 참조하고 배경, 범위, 제외 범위, 변경 파일, 검증 명령, 완료 조건과 후속 작업을 상세히 기록한다.
- 평상시 기능 개발은 Jira를 필수 작업 단위로 사용하고 GitHub Issue는 선택적으로 연결한다. 이번 기반 정리만 예외다.
- 인증 기능이 병합되기 전까지 `dev`에서는 Spring Security와 OAuth 의존성, 임시 계정, `SecurityConfig`를 제거한다.
- 인증은 별도 브랜치에서 개발하며, 병합할 때 의존성, 인증 계약, 보안 설정, 401/403 테스트와 관련 문서를 함께 추가한다.
- H2와 H2 Console은 완전히 제거한다.
- 로컬 실행, 통합 테스트와 CI는 PostgreSQL 16 + pgvector를 기준으로 한다.
- 빈 패키지와 `.gitkeep`은 미리 만들지 않는다.
- 구현되지 않은 미래 구조와 상세 인가 정책은 이번 범위에 포함하지 않는다.

## PR 구성

### PR 1: 기반 정리와 잘못된 성공 신호 제거

목적은 현재 애플리케이션의 불필요한 인증 장벽과 저장소 기본 오류를 제거하는 것이다.

포함:

- Spring Security 및 OAuth 의존성 제거
- 임시 `ssafy/ssafy` 계정과 `SecurityConfig` 제거
- Gradle Wrapper 실행 권한을 `100755`로 변경
- 존재하지 않는 `docs/feed/` 링크와 오래된 migration 주석 수정
- PR #8을 대체된 PR로 닫기

제외:

- 인증·인가 구현
- 도메인 패키지 골격
- 커버리지 임계값
- 상세 개발 규칙

완료 기준:

- `./gradlew clean check --no-daemon` 성공
- Security 자동 설정과 임시 계정이 classpath 및 설정에서 제거됨
- Wrapper를 `./gradlew`로 직접 실행할 수 있음

### PR 2: 재현 가능한 로컬 인프라

목적은 모든 팀원이 같은 PostgreSQL과 Redis 환경을 한 가지 명령 흐름으로 실행하게 만드는 것이다.

포함:

- PostgreSQL 16 + pgvector와 Redis 7 이미지 지정
- PostgreSQL `15432`, Redis `16379` 개발 포트 고정
- PostgreSQL과 Redis healthcheck
- `.env.example`과 실제 `.env` 제외 규칙
- PostgreSQL named volume
- 기동, 상태 확인, 종료와 초기화 명령 문서화

완료 기준:

- `docker compose config` 성공
- `docker compose up -d` 후 두 서비스가 `healthy`
- `docker compose down` 후 재기동 시 PostgreSQL 데이터 유지
- `docker compose down -v`로 초기화 가능

### PR 3: PostgreSQL 기반 테스트 하네스

목적은 Flyway와 PostgreSQL 전용 기능을 실제 PostgreSQL에서 검증하는 것이다.

포함:

- H2와 H2 Console 의존성 및 관련 문서 제거
- 기존 `flyway-core`를 Spring Boot 4.1용 `spring-boot-starter-flyway`로 교체
- PostgreSQL용 `flyway-database-postgresql` 유지
- Spring Boot 4.1 dependency management가 제공하는 Testcontainers 2.x 사용
- `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql` 적용
- `@ServiceConnection` 기반 PostgreSQL 테스트 연결
- 기존 Spring Context 테스트를 PostgreSQL Testcontainers 기반으로 전환
- 빈 DB migration 검증 테스트 추가

Migration 검증 범위:

- `core`, `ai` 스키마
- `vector` extension
- `public.flyway_schema_history`
- V1, V100, V101, V102 적용
- V100~V102의 핵심 테이블과 인덱스 존재

완료 기준:

- `./gradlew clean check --no-daemon`이 PostgreSQL 컨테이너를 사용해 성공
- Docker가 없으면 DB 테스트가 건너뛰지 않고 원인을 알 수 있게 실패
- 테스트 로그에 H2 JDBC URL이 없음
- 테스트 로그 또는 assertion으로 Flyway 실행이 확인됨
- Spring Boot 시작 시 Flyway 자동 설정이 활성화됨

### PR 4: CI와 브랜치 보호

목적은 로컬 완료 기준을 모든 PR의 필수 조건으로 전환하는 것이다.

포함:

- `dev` 대상 pull request와 push에서 실행되는 GitHub Actions
- JDK 21, Gradle Wrapper 검증과 캐시
- `./gradlew clean check --no-daemon`
- 실패 시에도 테스트 및 JaCoCo 산출물 업로드
- workflow timeout과 동일 브랜치의 이전 실행 취소
- 최소 GitHub 권한 `contents: read`
- `dev` branch protection

브랜치 보호:

- pull request 필수
- backend CI status check 필수
- unresolved conversation 차단
- direct push 차단

완료 기준:

- 테스트가 성공하는 PR에서 CI 통과
- 의도적으로 실패하는 테스트가 있는 PR에서 CI 실패
- `dev` 직접 push 거부

### PR 5: 팀 개발 규칙과 에이전트 하네스

목적은 사람과 에이전트가 같은 시작 절차와 완료 기준을 사용하게 만드는 것이다.

포함:

- `docs/setup/backend-initial-setup.md`
- `CONTRIBUTING.md`
- `docs/development/api-conventions.md`
- `docs/development/database-conventions.md`
- `docs/development/testing-conventions.md`
- `CLAUDE.md`
- `AGENTS.md`
- `.claude/settings.json`
- `.claude/settings.local.json` Git 제외
- Jira 중심 PR·브랜치·커밋 규칙과 템플릿

문서 책임:

- `docs/setup/backend-initial-setup.md`: 이번 정리의 배경, 문제, 상위 Issue와 5개 PR, 진행 순서 및 완료 기준을 설명하는 팀 공유 문서
- `CONTRIBUTING.md`: 사람과 모든 도구가 참고하는 팀 규칙의 단일 원본
- `docs/development/`: API, DB와 테스트의 상세 규칙
- `CLAUDE.md`: 에이전트 작업 순서, 필수 선행 문서와 검증 명령을 담는 짧은 실행 하네스
- `AGENTS.md`: 다른 에이전트가 먼저 `CLAUDE.md`를 읽고 준수하도록 연결하는 짧은 파일
- `.claude/settings.json`: 저장소에서 공유해도 되는 Claude 설정만 포함
- `.claude/settings.local.json`: 개인별 권한과 환경 설정

규칙은 여러 파일에 복제하지 않는다. `CONTRIBUTING.md`를 원본으로 두고 `CLAUDE.md`, `AGENTS.md`, README와 템플릿은 필요한 문서로 연결한다.

완료 기준:

- 신규 팀원이 `CONTRIBUTING.md`만으로 인프라 기동, 앱 실행, 테스트와 PR 생성 가능
- Codex 계열 도구는 `AGENTS.md`에서 `CLAUDE.md`와 `CONTRIBUTING.md`로 연결됨
- Claude Code는 `CLAUDE.md`에서 같은 규칙으로 연결됨
- 팀 설정과 개인 설정이 Git 추적 여부로 분리됨

## 실행 및 테스트 계약

로컬 기본 흐름:

```bash
cp .env.example .env
docker compose up -d
docker compose ps
./gradlew bootRun
```

전체 검증:

```bash
./gradlew clean check --no-daemon
```

원칙:

- 순수 도메인과 Service 단위 테스트는 Spring Context와 DB 없이 실행한다.
- Repository, Flyway와 DB 통합 테스트는 PostgreSQL Testcontainers를 사용한다.
- migration 변경 PR은 빈 PostgreSQL DB migration 테스트를 반드시 통과한다.
- Docker는 백엔드 전체 검증의 필수 사전 조건이다.
- CI와 로컬은 동일한 Gradle 검증 명령을 사용한다.

## 인증 브랜치 경계

이번 기반 정리에서는 모든 일반 API를 무인증으로 개발할 수 있다. 인증 브랜치는 `dev`를 자주 반영해 장기 브랜치 충돌을 줄인다.

인증 병합 PR은 최소한 다음을 함께 제공해야 한다.

- 필요한 Spring Security 및 인증 의존성
- 인증 principal과 토큰 또는 세션 계약
- 공개 경로와 보호 경로
- 인증 실패 401, 권한 부족 403 또는 리소스 은닉 404 정책
- 성공, 미인증과 권한 부족 테스트
- 로컬 개발 인증 방법
- Security 개발 규칙 문서

## GitHub 상위 Issue 계약

상위 Issue에는 다음을 기록한다.

- 현재 문제: 충돌 상태의 PR #8, 실행 불가능한 Wrapper, 실행되지 않는 Flyway, H2의 잘못된 성공 신호, 재현되지 않는 Compose, CI와 branch protection 부재, 문서 불일치
- 목표 상태: PostgreSQL 기준의 실행·테스트·CI 하네스와 공유 가능한 팀 규칙
- 5개 PR 체크리스트와 의존 순서
- 이번 작업에서 제외하는 인증·인가 구현과 도메인 기능
- 각 PR 링크와 진행 상태

각 PR 본문은 상위 Issue를 참조하되 독립적으로 리뷰할 수 있을 만큼 상세해야 한다.

## 완료 정의

모든 PR 병합 후 신규 팀원은 빈 환경에서 다음을 수행할 수 있어야 한다.

```bash
git clone https://github.com/Team-PinLog/back.git
cd back
cp .env.example .env
docker compose up -d
docker compose ps
./gradlew bootRun
./gradlew clean check --no-daemon
```

최종 상태:

- PostgreSQL과 Redis가 `healthy`
- Flyway가 빈 PostgreSQL에 전체 migration 적용
- H2가 classpath와 문서에 없음
- 인증 병합 전 일반 API 개발이 Security에 막히지 않음
- PR에서 로컬과 동일한 검증이 필수 실행
- `dev` 직접 push 차단
- 빈 패키지와 사용하지 않는 scaffold 없음
- 팀원과 에이전트가 같은 규칙 문서를 사용

## 범위 밖

- 실제 인증·인가 구현
- 도메인 기능과 API 구현
- 미래 도메인 패키지 미리 생성
- 전역 커버리지 수치 강제
- 운영 배포와 Kubernetes 구성
- PR #8의 상세 인가 정책 및 Git 규칙 전문을 그대로 병합
