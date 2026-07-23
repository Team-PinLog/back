# CLAUDE.md

이 파일은 이 저장소에서 작업하는 AI 에이전트(Claude Code 등)를 위한 가이드다.
사람용 개요는 [`README.md`](./README.md), 설계·구현 문서는 [`docs/`](./docs)를 본다.

## 프로젝트 개요

PinLog 백엔드. Spring Boot 4.1 / Java 21, Gradle Wrapper. PostgreSQL(pgvector) + Redis를
로컬 Docker Compose로 띄우고, DB 스키마는 Flyway로 마이그레이션한다.

## 자주 쓰는 명령 (Windows PowerShell)

```powershell
.\gradlew.bat bootRun    # 애플리케이션 실행
.\gradlew.bat test       # 테스트
.\gradlew.bat build      # 컴파일 + 테스트 + JaCoCo 리포트
docker compose up -d     # 로컬 인프라 기동
docker compose down      # 로컬 인프라 종료
```

- 테스트 리포트: `build/reports/tests/test/index.html`
- 커버리지 리포트: `build/reports/jacoco/test/html/index.html`

## 반드시 알아야 할 것

- **Context path `/api/core`**: `server.servlet.context-path`가 `/api/core`다. 컨트롤러
  매핑에 `/api/core`를 다시 붙이지 않는다. 실제 주소는 `http://localhost:8080/api/core/...`.
- **Flyway**: 스키마 변경은 `src/main/resources/db/migration`에 마이그레이션으로 추가한다.
  JPA `ddl-auto`는 `validate`이므로 엔티티가 마이그레이션 결과와 일치해야 한다. 스키마 생성은
  `V1__create_schemas.sql`이 전담하며, `spring.flyway.schemas`는 지정하지 않는다.
- **Actuator 노출**: `health`, `prometheus`만 노출하고 SecurityConfig에서 인증 없이 허용한다.
- **임시 개발 계정**(`ssafy`/`ssafy`)은 실제 인증·인가 구현으로 교체될 예정이다.

## 패키지 구조 (도메인형)

베이스 패키지: `com.pinlog.pinlogback`

```text
domain/<feature>/{controller, service, repository, entity, dto}   # 기능 단위
global/{config, common, exception, security}                       # 전역 관심사
```

현재 도메인:

| 패키지 | 도메인 | 주요 엔티티/기능 |
| --- | --- | --- |
| `account` | 계정 | User · 가입/로그인/로그아웃/찾기/탈퇴/정보수정 (소셜 추후) |
| `place` | 지도/장소 | Place · 장소 저장·조회, Kakao 검색 연동 |
| `record` | 기록 | Record, Context · 기록 CRUD, 자연어 검색 |
| `collection` | 컬렉션 | Collection · CRUD, Record 추가/제거, 공개 토글, 키워드 |
| `feed` | 발견 | 발행 컬렉션·장소 추천, Feed 상세 |
| `profile` | 프로필 | Setting · 내 프로필 조회, 팔로잉·팔로워 수, 개인설정 |
| `follow` | 팔로우 | Follow(User→User) · 팔로우/해제, 별칭 수정 |

- 새 기능은 `domain/<feature>` 아래에 위 레이어로 추가한다.
- 전역 설정/공통 유틸/예외/보안은 `global/` 아래에 둔다. (예: `global/config/SecurityConfig`)
- 빈 패키지는 `.gitkeep`으로 추적 중이며, 실제 코드가 생기면 제거한다.
- Shelf는 ERD에서 제거됨(팔로우는 User를 대상으로 함).

## 인가 규칙

인가 정책은 [`docs/authorization.md`](./docs/authorization.md)가 단일 기준이다.
소유 리소스는 조회 후 `resource.ownerId == currentUserId`를 검증하고, 타인의 비공개
리소스는 존재를 숨기기 위해 `403`이 아니라 `404`를 반환한다.

## 커밋 / 브랜치 워크플로우

- 커밋 메시지는 [`docs/git-convention.md`](./docs/git-convention.md)(Conventional Commits)를 따른다.
- `dev`에 직접 커밋하지 않는다. GitHub 이슈를 먼저 만들고
  (`gh issue develop <번호> --base dev --checkout`) 이슈-연결 브랜치에서 작업한다.

## 커버리지 게이트 (JaCoCo)

`build.gradle`에 JaCoCo가 설정되어 있고 `test` 실행 시 리포트가 생성된다.
라인 커버리지 **70% 미만이면 `check`(및 `build`)가 실패**한다
(`jacocoTestCoverageVerification` 게이트가 `check`에 연결됨).
