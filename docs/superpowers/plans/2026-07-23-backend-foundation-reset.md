# Backend Foundation Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 최신 `dev`에서 PinLog 백엔드의 실행·DB·테스트·CI·팀 규칙을 신뢰할 수 있는 PostgreSQL 기반 개발 하네스로 다시 구성한다.

**Architecture:** 충돌 상태인 PR #8은 폐기하고 하나의 GitHub 상위 Issue 아래 5개 작은 PR을 순서대로 병합한다. 인증 병합 전에는 Security를 제거하며, 자동 검사 가능한 규칙은 Gradle·Testcontainers·GitHub Actions·branch protection으로 강제하고 나머지는 `CONTRIBUTING.md`를 단일 원본으로 문서화한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Gradle 9.5.1 Wrapper, Flyway 12.4, PostgreSQL 16 + pgvector 0.8.1, Redis 7.4.5, Testcontainers 2.0.5, JUnit 6, JaCoCo, GitHub Actions

**Tracking Issue:** [#9 chore: rebuild backend development foundation](https://github.com/Team-PinLog/back/issues/9)

## Global Constraints

- 기준 브랜치는 최신 `dev`다.
- 기존 PR #8은 병합하거나 cherry-pick하지 않고 대체된 PR로 닫는다.
- 이번 기반 정리는 Jira를 사용하지 않고 하나의 GitHub 상위 Issue로 관리한다.
- 평상시 기능 개발은 Jira를 필수 작업 단위로 사용하며 이번 작업만 예외다.
- 각 PR은 이전 PR이 `dev`에 병합된 후 최신 `dev`에서 분기한다.
- 인증 병합 전까지 Spring Security, OAuth, 임시 사용자와 `SecurityConfig`를 `dev`에 두지 않는다.
- H2와 H2 Console을 사용하지 않는다.
- DB를 사용하는 모든 테스트는 PostgreSQL Testcontainers를 사용한다.
- Spring Boot dependency management가 관리하는 Flyway와 Testcontainers 버전을 임의 BOM으로 덮지 않는다.
- 빈 패키지와 `.gitkeep`을 만들지 않는다.
- 전역 커버리지 임계값을 두지 않되 JaCoCo XML/HTML 보고서는 생성한다.
- 모든 PR은 `./gradlew clean check --no-daemon`과 해당 PR의 추가 검증을 통과해야 한다.

---

### Task 1: GitHub 상위 Issue 등록 및 PR #8 종료

**Files:**
- Reference: `docs/superpowers/specs/2026-07-23-backend-foundation-reset-design.md`
- No repository file changes

**Interfaces:**
- Consumes: 승인된 기반 정리 설계
- Produces: 이후 5개 PR이 참조할 셸 변수 `$FOUNDATION_ISSUE`

- [ ] **Step 1: GitHub 인증과 저장소를 확인한다**

Run:

```bash
gh auth status
gh repo view Team-PinLog/back --json nameWithOwner,defaultBranchRef
```

Expected: 인증된 계정과 기본 브랜치 `dev`가 출력된다.

- [ ] **Step 2: 상위 Issue를 생성한다**

Run:

```bash
gh issue create \
  --repo Team-PinLog/back \
  --title "chore: rebuild backend development foundation" \
  --body $'## 배경\n현재 백엔드에는 충돌 상태의 PR #8, 실행 권한이 없는 Gradle Wrapper, 실행되지 않는 Flyway, H2 기반의 잘못된 테스트 성공 신호, 재현되지 않는 Compose 설정, CI와 branch protection 부재, 문서와 실제 설정의 불일치가 있습니다.\n\n## 목표\nPostgreSQL 기준의 로컬 실행, Flyway 검증, 테스트, CI와 팀 개발 규칙을 최신 dev에서 작은 PR로 다시 구성합니다.\n\n## 작업 순서\n- [ ] PR 1: 기반 정리와 Security 제거\n- [ ] PR 2: 재현 가능한 PostgreSQL·Redis 환경\n- [ ] PR 3: PostgreSQL Testcontainers와 Flyway 검증\n- [ ] PR 4: GitHub Actions와 branch protection\n- [ ] PR 5: CONTRIBUTING 및 에이전트 하네스\n\n## 이번 작업의 예외\n이번 기반 정리는 Jira를 사용하지 않습니다. 각 PR은 이 Issue를 참조합니다. 평상시 기능 개발은 Jira를 작업 단위로 사용합니다.\n\n## 범위 밖\n- 실제 인증·인가 구현\n- 도메인 기능과 API 구현\n- 빈 도메인 패키지 생성\n- 전역 커버리지 임계값\n- 운영 배포와 Kubernetes 설정\n\n## 완료 조건\n- PostgreSQL과 Redis가 healthy 상태로 기동\n- Flyway 전체 migration을 빈 PostgreSQL에서 검증\n- H2와 임시 Security 제거\n- 모든 PR에서 동일한 Gradle check 필수 실행\n- dev 직접 push 차단\n- 사람과 에이전트가 같은 팀 규칙 사용'
```

Expected: 생성된 Issue URL이 출력된다.

Issue 번호를 변수로 읽고 검증한다.

```bash
FOUNDATION_ISSUE=$(gh issue list \
  --repo Team-PinLog/back \
  --state open \
  --search '"chore: rebuild backend development foundation" in:title' \
  --json number,title \
  --jq 'map(select(.title == "chore: rebuild backend development foundation")) | first | .number')
test -n "$FOUNDATION_ISSUE"
echo "$FOUNDATION_ISSUE"
```

- [ ] **Step 3: PR #8을 대체된 PR로 닫는다**

Run:

```bash
gh pr close 8 \
  --repo Team-PinLog/back \
  --comment "이 PR은 최신 dev 기준의 backend foundation reset 작업으로 대체합니다. 후속 작업은 #${FOUNDATION_ISSUE}에서 작은 PR로 나누어 진행합니다."
```

Expected: PR #8 상태가 `CLOSED`다.

- [ ] **Step 4: Issue 상태를 확인한다**

Run:

```bash
gh issue view "$FOUNDATION_ISSUE" --repo Team-PinLog/back
gh pr view 8 --repo Team-PinLog/back --json state
```

Expected: 상위 Issue는 `OPEN`, PR #8은 `CLOSED`.

---

### Task 2: PR 1 — 기반 정리와 Security 제거

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yml`
- Delete: `src/main/java/com/pinlog/pinlogback/config/SecurityConfig.java`
- Modify mode: `gradlew` from `100644` to `100755`
- Modify: `README.md`
- Modify: `src/main/resources/db/migration/V102__feed_event.sql`
- Include: `docs/superpowers/specs/2026-07-23-backend-foundation-reset-design.md`

**Interfaces:**
- Consumes: 최신 `dev`, `$FOUNDATION_ISSUE`
- Produces: 인증 장벽 없이 실행되고 `./gradlew`로 검증 가능한 기준선

- [ ] **Step 1: PR 1 브랜치를 만든다**

현재 로컬 `dev`에는 설계 커밋 `7fb4e7e`가 있으므로 이를 포함해 브랜치를 만든다.

```bash
git switch -c chore/backend-foundation-cleanup
git status --short --branch
```

Expected: `chore/backend-foundation-cleanup`, 변경 파일 없음.

- [ ] **Step 2: 제거 전 Security 자동 설정 테스트를 실행한다**

Run:

```bash
./gradlew test --tests '*DeploymentContractTests' --no-daemon
```

Expected: 기존 테스트는 성공하지만 애플리케이션 로그에 generated security password 또는 Security filter chain이 존재할 수 있다.

- [ ] **Step 3: Security와 OAuth 의존성을 제거한다**

`build.gradle`에서 다음 네 줄을 제거한다.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-security-oauth2-client'
testImplementation 'org.springframework.boot:spring-boot-starter-security-oauth2-client-test'
testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
```

`src/main/resources/application.yml`에서 다음 블록을 제거한다.

```yaml
security:
  user:
    name: ssafy
    password: ssafy
```

`src/main/java/com/pinlog/pinlogback/config/SecurityConfig.java`를 삭제한다.

- [ ] **Step 4: Wrapper 권한과 깨진 링크를 수정한다**

Run:

```bash
git update-index --chmod=+x gradlew
```

`README.md`의 `docs/feed/` 항목을 제거하고 Feed 문서는 `docs/ai/spec/`에 포함되어 있다고 설명한다. `V102__feed_event.sql`의 근거 경로를 다음으로 바꾼다.

```sql
-- 근거: docs/ai/spec/feed-event.md §2.
```

- [ ] **Step 5: 정적 검증을 실행한다**

Run:

```bash
git ls-files -s gradlew
rg -n 'spring-security|oauth2-client|ssafy/ssafy|docs/feed' build.gradle src README.md docs
```

Expected:

- `gradlew` mode가 `100755`
- 제거 대상 검색 결과 없음

- [ ] **Step 6: 전체 검증을 실행한다**

Run:

```bash
./gradlew clean check --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: PR 1 변경을 커밋한다**

```bash
git add build.gradle src/main/resources/application.yml \
  src/main/resources/db/migration/V102__feed_event.sql README.md gradlew
git add -u src/main/java/com/pinlog/pinlogback/config/SecurityConfig.java
git commit -m "chore: remove premature security setup"
```

- [ ] **Step 8: PR 1을 생성한다**

PR 본문은 아래 내용을 사용한다.

```markdown
## 배경
실제 인증 계약이 없는 상태에서 임시 Basic/Form Login과 고정 계정이 기능 개발을 막고 있었습니다. Gradle Wrapper 실행 권한과 문서 링크도 실제 저장소 상태와 달랐습니다.

## 범위
- Spring Security/OAuth와 임시 계정 제거
- SecurityConfig 제거
- gradlew 실행 권한 수정
- 깨진 docs/feed 링크와 migration 주석 수정
- 기반 정리 설계 문서 포함

## 제외 범위
- 실제 인증·인가
- H2/Testcontainers 전환
- CI와 개발 규칙

## 검증
- `./gradlew clean check --no-daemon`
- `git ls-files -s gradlew`
- Security 및 깨진 링크 문자열 검색

## 완료 조건
- 인증 없이 애플리케이션 기능 개발 가능
- `./gradlew` 직접 실행 가능
- 기존 테스트 성공

Refs #${FOUNDATION_ISSUE}
```

Run:

```bash
git push -u origin chore/backend-foundation-cleanup
gh pr create \
  --base dev \
  --title "chore: clean backend foundation and remove premature security" \
  --body $'## 배경\n실제 인증 계약이 없는 상태에서 임시 Basic/Form Login과 고정 계정이 기능 개발을 막고 있었습니다. Gradle Wrapper 실행 권한과 문서 링크도 실제 저장소 상태와 달랐습니다.\n\n## 범위\n- Spring Security/OAuth와 임시 계정 제거\n- SecurityConfig 제거\n- gradlew 실행 권한 수정\n- 깨진 문서 링크 수정\n\n## 제외 범위\n- 실제 인증·인가\n- H2/Testcontainers 전환\n- CI와 개발 규칙\n\n## 검증\n- ./gradlew clean check --no-daemon\n- git ls-files -s gradlew\n- 제거 대상 문자열 검색\n\n## 완료 조건\n- 인증 없이 애플리케이션 기능 개발 가능\n- ./gradlew 직접 실행 가능\n- 기존 테스트 성공\n\nRefs #'"$FOUNDATION_ISSUE"
```

Expected: PR URL 출력. 리뷰와 CI 성공 후 병합한다.

---

### Task 3: PR 2 — 재현 가능한 로컬 인프라

**Files:**
- Modify: `compose.yaml`
- Create: `.env.example`
- Modify: `.gitignore`
- Modify: `README.md`

**Interfaces:**
- Consumes: PR 1이 병합된 최신 `dev`
- Produces: PostgreSQL `15432`, Redis `16379`, 두 healthcheck와 `postgres-data` volume

- [ ] **Step 1: 최신 `dev`에서 브랜치를 만든다**

```bash
git switch dev
git pull --ff-only origin dev
git switch -c chore/reproducible-local-infrastructure
```

- [ ] **Step 2: `.env.example`을 작성한다**

```dotenv
POSTGRES_DB=pinlog
POSTGRES_USER=pinlog
POSTGRES_PASSWORD=pinlog-local
POSTGRES_PORT=15432
REDIS_PORT=16379
```

`.gitignore`에 다음을 추가한다.

```gitignore
# Local environment
.env
!.env.example
```

- [ ] **Step 3: `compose.yaml`을 확정한다**

```yaml
services:
  postgres:
    image: pgvector/pgvector:0.8.1-pg16
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-pinlog}
      POSTGRES_USER: ${POSTGRES_USER:-pinlog}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-pinlog-local}
    ports:
      - "${POSTGRES_PORT:-15432}:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-pinlog} -d ${POSTGRES_DB:-pinlog}"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 10s

  redis:
    image: redis:7.4.5-alpine
    ports:
      - "${REDIS_PORT:-16379}:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  postgres-data:
```

- [ ] **Step 4: README 실행 계약을 갱신한다**

아래 한 가지 흐름만 기본 실행 방법으로 둔다.

```bash
cp .env.example .env
docker compose up -d
docker compose ps
./gradlew bootRun
```

종료와 초기화 명령을 구분한다.

```bash
docker compose down
docker compose down -v
```

- [ ] **Step 5: Compose를 정적·동적으로 검증한다**

Run:

```bash
cp .env.example .env
docker compose config
docker compose up -d --wait
docker compose ps
docker compose exec postgres pg_isready -U pinlog -d pinlog
docker compose exec redis redis-cli ping
```

Expected: 두 서비스 `healthy`, PostgreSQL `accepting connections`, Redis `PONG`.

- [ ] **Step 6: 재시작과 초기화를 검증한다**

Run:

```bash
docker compose exec postgres psql -U pinlog -d pinlog -c 'CREATE TABLE public.setup_probe(id int primary key);'
docker compose down
docker compose up -d --wait
docker compose exec postgres psql -U pinlog -d pinlog -c '\dt public.setup_probe'
docker compose down -v
```

Expected: 일반 재시작 후 `setup_probe` 존재, `down -v` 성공.

- [ ] **Step 7: 검증 후 커밋하고 PR을 생성한다**

```bash
./gradlew clean check --no-daemon
git add compose.yaml .env.example .gitignore README.md
git commit -m "chore: make local infrastructure reproducible"
git push -u origin chore/reproducible-local-infrastructure
```

PR 본문 필수 항목:

```markdown
## 배경
Compose 이미지, 포트, 계정과 준비 상태가 팀원별로 달라 로컬 실행을 재현할 수 없었습니다.

## 범위
- pgvector 0.8.1/PostgreSQL 16과 Redis 7.4.5
- 고정 개발 포트와 환경변수 계약
- healthcheck와 PostgreSQL named volume
- 시작·종료·초기화 문서

## 제외 범위
- Testcontainers
- 운영 secret과 배포 설정

## 검증
- `docker compose config`
- `docker compose up -d --wait`
- PostgreSQL `pg_isready`
- Redis `PING`
- volume 재시작 검증
- `./gradlew clean check --no-daemon`

Refs #${FOUNDATION_ISSUE}
```

---

### Task 4: PR 3 — H2 제거와 PostgreSQL Testcontainers

**Files:**
- Modify: `build.gradle`
- Create: `src/test/java/com/pinlog/pinlogback/integration/PostgresContainerSupport.java`
- Create: `src/test/java/com/pinlog/pinlogback/integration/FlywayMigrationTests.java`
- Modify: `src/test/java/com/pinlog/pinlogback/PinlogBackApplicationTests.java`
- Modify: `src/test/java/com/pinlog/pinlogback/DeploymentContractTests.java`
- Delete: `docs/ai/troubleshooting/h2-pgvector-incompat.md`
- Modify: `docs/ai/troubleshooting/README.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: `pgvector/pgvector:0.8.1-pg16`
- Produces: 모든 Spring Context 테스트가 공유하는 `PostgresContainerSupport.POSTGRES`

- [ ] **Step 1: 최신 `dev`에서 브랜치를 만든다**

```bash
git switch dev
git pull --ff-only origin dev
git switch -c test/postgres-test-harness
```

- [ ] **Step 2: 현재 잘못된 성공 신호를 기록한다**

Run:

```bash
./gradlew clean test --no-daemon
rg -n 'jdbc:h2:' build/test-results/test
rg -n 'Flyway|Migrating schema' build/test-results/test
```

Expected: H2 URL 존재, Flyway migration 실행 근거 없음.

- [ ] **Step 3: 의존성을 PostgreSQL 기준으로 교체한다**

`build.gradle`에서 제거:

```groovy
implementation 'org.springframework.boot:spring-boot-h2console'
implementation 'org.flywaydb:flyway-core'
runtimeOnly 'com.h2database:h2'
```

추가 또는 유지:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-flyway'
implementation 'org.flywaydb:flyway-database-postgresql'
runtimeOnly 'org.postgresql:postgresql'
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
testImplementation 'org.testcontainers:testcontainers-postgresql'
```

별도 Testcontainers BOM이나 버전 문자열은 추가하지 않는다.

- [ ] **Step 4: 공유 컨테이너 지원 클래스를 작성한다**

```java
package com.pinlog.pinlogback.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class PostgresContainerSupport {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg16")
                .asCompatibleSubstituteFor("postgres")
        );
}
```

- [ ] **Step 5: 기존 Context 테스트를 PostgreSQL로 전환한다**

각 테스트 클래스에 `@Testcontainers`를 추가하고 `PostgresContainerSupport`를 상속한다. 각 클래스에 다음 필드를 선언해 JUnit extension이 컨테이너를 관리하게 한다.

```java
@Container
static final PostgreSQLContainer<?> postgres = POSTGRES;
```

`DeploymentContractTests`의 기존 HTTP assertion은 유지한다.

- [ ] **Step 6: 실패하는 migration 검증 테스트를 작성한다**

`FlywayMigrationTests`는 다음을 검증한다.

```java
package com.pinlog.pinlogback.integration;

import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class FlywayMigrationTests extends PostgresContainerSupport {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres = POSTGRES;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway flyway;

    @Test
    void appliesAllMigrationsToEmptyPostgres() {
        Set<String> versions = java.util.Arrays.stream(flyway.info().applied())
            .map(info -> info.getVersion().getVersion())
            .collect(Collectors.toSet());

        assertThat(versions).contains("1", "100", "101", "102");
        assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('core','ai')"))
            .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM pg_extension WHERE extname = 'vector'"))
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'"))
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ai'"))
            .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'core' AND table_name = 'feed_event'"))
            .isEqualTo(1);
    }

    private int count(String sql) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class);
        return result == null ? 0 : result;
    }
}
```

- [ ] **Step 7: PostgreSQL 테스트를 실행한다**

Run:

```bash
./gradlew test --tests '*FlywayMigrationTests' --no-daemon
./gradlew clean check --no-daemon
rg -n 'jdbc:h2:' build/test-results/test
```

Expected: 두 Gradle 명령 성공, H2 검색 결과 없음.

- [ ] **Step 8: H2 문서를 제거하고 README를 갱신한다**

- `docs/ai/troubleshooting/h2-pgvector-incompat.md` 삭제
- troubleshooting index에서 해당 링크 제거
- README 기술 스택과 설정 참고에서 H2 제거
- 테스트 사전 조건에 Docker 명시

- [ ] **Step 9: 커밋하고 PR을 생성한다**

```bash
git add build.gradle src/test README.md docs/ai/troubleshooting/README.md
git add -u docs/ai/troubleshooting/h2-pgvector-incompat.md
git commit -m "test: verify migrations on PostgreSQL"
git push -u origin test/postgres-test-harness
```

PR 본문에는 의존성 변경, H2 제거 이유, Testcontainers 2.x artifact 이름, migration assertion, Docker 필수 조건과 아래 검증을 기록한다.

```markdown
## 검증
- `./gradlew test --tests '*FlywayMigrationTests' --no-daemon`
- `./gradlew clean check --no-daemon`
- 테스트 결과에서 `jdbc:h2:` 미검출

Refs #${FOUNDATION_ISSUE}
```

---

### Task 5: PR 4 — GitHub Actions와 branch protection

**Files:**
- Create: `.github/workflows/backend-ci.yml`
- Modify: `build.gradle`
- Modify: `.github/pull_request_template.md`
- External: GitHub `dev` branch protection

**Interfaces:**
- Consumes: `./gradlew clean check --no-daemon`, Docker 기반 Testcontainers
- Produces: required status check `backend-ci / check`

- [ ] **Step 1: 최신 `dev`에서 브랜치를 만든다**

```bash
git switch dev
git pull --ff-only origin dev
git switch -c ci/backend-quality-gate
```

- [ ] **Step 2: JaCoCo 보고서만 추가한다**

`build.gradle` plugin에 추가:

```groovy
id 'jacoco'
```

task 설정:

```groovy
tasks.named('test') {
    useJUnitPlatform()
    finalizedBy tasks.named('jacocoTestReport')
}

tasks.named('jacocoTestReport') {
    dependsOn tasks.named('test')
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named('check') {
    dependsOn tasks.named('jacocoTestReport')
}
```

커버리지 violation rule은 추가하지 않는다.

- [ ] **Step 3: CI workflow를 작성한다**

```yaml
name: backend-ci

on:
  pull_request:
    branches: [dev]
  push:
    branches: [dev]

permissions:
  contents: read

concurrency:
  group: backend-ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  check:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/actions/wrapper-validation@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v4
      - name: Run checks
        run: ./gradlew clean check --no-daemon
      - name: Upload test and coverage reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: backend-check-reports
          path: |
            build/reports/tests/
            build/reports/jacoco/
          if-no-files-found: warn
          retention-days: 14
```

- [ ] **Step 4: PR 템플릿의 검증 항목을 강제한다**

다음 체크리스트를 추가한다.

```markdown
## 테스트 / 검증
- [ ] `./gradlew clean check --no-daemon`
- [ ] DB 변경 시 PostgreSQL 통합 테스트
- [ ] migration 변경 시 빈 DB migration 테스트
- [ ] API 계약 변경 시 관련 문서 갱신
```

- [ ] **Step 5: 로컬 검증 후 커밋하고 PR을 생성한다**

```bash
./gradlew clean check --no-daemon
test -f build/reports/jacoco/test/html/index.html
test -f build/reports/jacoco/test/jacocoTestReport.xml
git add build.gradle .github
git commit -m "ci: enforce backend quality checks"
git push -u origin ci/backend-quality-gate
```

- [ ] **Step 6: 성공과 실패 경로를 검증한다**

PR에서 `backend-ci / check` 성공을 확인한다. 임시 커밋에서 assertion 하나를 실패시켜 status check 실패를 확인한 뒤 해당 임시 커밋을 되돌린다.

```bash
PR_NUMBER=$(gh pr view --json number --jq .number)
gh pr checks "$PR_NUMBER" --watch
```

- [ ] **Step 7: `dev` branch protection을 설정한다**

PR 4가 병합되고 status check 이름이 생성된 후 저장소 설정에서 다음을 적용한다.

```text
Require a pull request before merging
Require status checks to pass: backend-ci / check
Require conversation resolution before merging
Block direct pushes to dev
```

Run:

```bash
gh api repos/Team-PinLog/back/branches/dev/protection
```

Expected: protection 설정 JSON 반환.

---

### Task 6: PR 5 — 팀 공유 문서와 에이전트 하네스

**Files:**
- Create: `docs/setup/backend-initial-setup.md`
- Create: `CONTRIBUTING.md`
- Create: `docs/development/api-conventions.md`
- Create: `docs/development/database-conventions.md`
- Create: `docs/development/testing-conventions.md`
- Create: `CLAUDE.md`
- Create: `AGENTS.md`
- Create: `.claude/settings.json`
- Create: `.claude/settings.local.json.example`
- Modify: `.gitignore`
- Modify: `README.md`
- Modify: `docs/README.md`
- Modify: `.github/pull_request_template.md`
- Modify: `.github/ISSUE_TEMPLATE/*.md`

**Interfaces:**
- Consumes: PR 1~4에서 검증된 실제 명령
- Produces: 사람과 에이전트가 공유하는 단일 개발 계약

- [ ] **Step 1: 최신 `dev`에서 브랜치를 만든다**

```bash
git switch dev
git pull --ff-only origin dev
git switch -c docs/backend-development-harness
```

- [ ] **Step 2: 팀 공유용 정리 문서를 작성한다**

`docs/setup/backend-initial-setup.md`는 다음 순서로 작성한다.

```markdown
# Backend 초기 기반 정리

## 왜 다시 정리했는가
PR #8 충돌, Wrapper 권한, 실행되지 않던 Flyway, H2의 잘못된 성공 신호, Compose 불일치, CI 부재를 설명한다.

## 무엇을 바꿨는가
PR 1~5의 목적, 링크, 검증 결과를 표로 기록한다.

## 현재 개발 시작 방법
`.env` 생성, Compose 기동, `bootRun`, `clean check` 명령을 기록한다.

## 인증 작업 경계
현재는 무인증이며 인증 PR이 제공해야 할 의존성·계약·테스트를 기록한다.

## 팀 규칙 위치
`CONTRIBUTING.md`, `docs/development/`, `CLAUDE.md`, `AGENTS.md`의 책임을 설명한다.
```

상위 Issue와 각 PR의 실제 URL을 넣고 작성 중 표식을 남기지 않는다.

- [ ] **Step 3: `CONTRIBUTING.md`를 단일 진입점으로 작성한다**

반드시 확정형으로 포함한다.

```text
사전 준비: JDK 21, Docker
로컬 시작: cp .env.example .env → docker compose up -d --wait → ./gradlew bootRun
작업 추적: Jira 필수, GitHub Issue 선택
이번 foundation reset만 GitHub Issue 단독 예외
브랜치: `{type}/{jira-key}-{summary}`
커밋: `{type}({jira-key}): {summary}`
검증: ./gradlew clean check --no-daemon
DB 변경: PostgreSQL 통합 테스트 필수
migration 변경: 빈 DB migration 테스트 필수
인증 변경: 성공/401/403 또는 404 테스트 필수
빈 패키지와 .gitkeep 금지
```

- [ ] **Step 4: 상세 개발 규칙을 작성한다**

`database-conventions.md`:

- PostgreSQL만 지원
- V1 공통, V2~V99 백엔드, V100~V199 AI 소유
- 기존 migration 수정 금지, 새 버전 추가
- migration마다 빈 DB Testcontainers 검증
- `ddl-auto=validate`

`testing-conventions.md`:

- 순수 단위 테스트에는 Spring Context 금지
- Repository/Flyway는 PostgreSQL Testcontainers
- Docker 미실행 시 skip 금지
- PR 종류별 필수 테스트

`api-conventions.md`:

- context path `/api/core`
- Controller mapping에 `/api/core` 중복 금지
- URI는 복수 명사
- Entity와 request/response DTO 분리
- 시간은 ISO-8601 UTC
- validation 실패는 HTTP 400
- pagination은 `page`, `size`, `sort`
- 공통 오류 필드는 `code`, `message`, `traceId`

- [ ] **Step 5: 에이전트 연결 파일을 작성한다**

`AGENTS.md`:

```markdown
# Agent Instructions

Before changing this repository, read and follow `CLAUDE.md`.
`CLAUDE.md` points to the canonical human and engineering rules.
When instructions conflict, repository rules and the current user request take precedence.
```

`CLAUDE.md`:

```markdown
# PinLog Backend Agent Harness

1. Read `CONTRIBUTING.md`.
2. Read the relevant file under `docs/development/`.
3. Inspect existing tests before changing code.
4. Write or update the failing test first.
5. Use PostgreSQL Testcontainers for every DB-dependent test.
6. Do not add H2, empty packages, `.gitkeep`, or speculative domain layers.
7. Do not add Security until the authentication PR includes its full contract and tests.
8. Run `./gradlew clean check --no-daemon` before reporting completion.
9. If code and documentation conflict, stop and record the conflict in the PR.
```

- [ ] **Step 6: Claude 팀 설정과 개인 설정 경계를 작성한다**

`.claude/settings.json`:

```json
{
  "permissions": {
    "deny": [
      "Bash(git push --force:*)",
      "Bash(git reset --hard:*)"
    ]
  }
}
```

`.claude/settings.local.json.example`:

```json
{
  "permissions": {
    "allow": [
      "Bash(./gradlew:*)",
      "Bash(docker compose:*)"
    ]
  }
}
```

`.gitignore`:

```gitignore
.claude/settings.local.json
```

- [ ] **Step 7: Jira 중심 템플릿을 통일한다**

PR과 Issue 템플릿 모두 Jira 키를 필수 입력으로 두고 GitHub Issue는 선택 링크로 둔다. 이번 기반 정리 예외는 `CONTRIBUTING.md`에만 기록하며 일반 템플릿의 기본 규칙을 약화하지 않는다.

- [ ] **Step 8: 문서 링크와 명령을 검증한다**

Run:

```bash
rg -n 'docs/feed|ssafy/ssafy|jdbc:h2|spring-boot-h2console' README.md CONTRIBUTING.md CLAUDE.md AGENTS.md docs .github
./gradlew clean check --no-daemon
docker compose config
```

Expected: 제거 대상 문자열 없음, Gradle과 Compose 검증 성공.

- [ ] **Step 9: 신규 개발자 흐름을 처음부터 검증한다**

새 임시 clone에서 실행한다.

```bash
git clone https://github.com/Team-PinLog/back.git pinlog-back-acceptance
cd pinlog-back-acceptance
cp .env.example .env
docker compose up -d --wait
./gradlew clean check --no-daemon
```

Expected: 두 컨테이너 healthy, Flyway migration 성공, 전체 check 성공.

- [ ] **Step 10: 커밋하고 PR을 생성한다**

```bash
git add CONTRIBUTING.md CLAUDE.md AGENTS.md .claude .gitignore README.md docs .github
git commit -m "docs: define backend development harness"
git push -u origin docs/backend-development-harness
```

PR 본문은 생성 문서별 책임, Jira 기본 규칙과 이번 작업 예외, 에이전트 연결 흐름, 신규 clone 인수 테스트 결과를 상세히 기록한다.

```markdown
## 에이전트 규칙 흐름
Codex/AGENTS 지원 도구 → AGENTS.md → CLAUDE.md → CONTRIBUTING.md
Claude Code → CLAUDE.md → CONTRIBUTING.md
개발자 → CONTRIBUTING.md

## 검증
- 문서의 제거 대상 문자열 검색
- `docker compose config`
- `./gradlew clean check --no-daemon`
- 새 clone 인수 테스트

Refs #${FOUNDATION_ISSUE}
```

---

## Final Acceptance

모든 PR이 병합된 뒤 다음을 검증한다.

```bash
git switch dev
git pull --ff-only origin dev
cp .env.example .env
docker compose up -d --wait
docker compose ps
./gradlew bootRun
./gradlew clean check --no-daemon
gh api repos/Team-PinLog/back/branches/dev/protection
```

Expected:

- PostgreSQL과 Redis가 `healthy`
- Flyway V1, V100, V101, V102 적용
- H2와 Spring Security가 classpath에 없음
- JaCoCo XML/HTML 보고서 생성
- `backend-ci / check` 필수
- `dev` 직접 push 차단
- `CONTRIBUTING.md`, `CLAUDE.md`, `AGENTS.md` 연결 정상
- 상위 Issue의 PR 1~5 체크리스트 완료

상위 Issue에 최종 검증 결과를 댓글로 남기고 모든 체크박스를 완료한 뒤 Issue를 닫는다.
