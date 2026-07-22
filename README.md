# PinLog Backend

PinLog 백엔드 애플리케이션입니다. Spring Boot와 Java 21을 기반으로 하며, 로컬 개발 인프라는 Docker Compose로 실행합니다.

## 기술 스택

- Java 21
- Spring Boot 4.1.0
- Gradle 9.5.1 (Gradle Wrapper)
- Spring Web MVC
- Spring Data JPA
- Spring Data Redis
- Spring Security / OAuth 2.0 Client
- Spring Boot Actuator / Micrometer Prometheus
- PostgreSQL
- Redis
- H2
- Lombok
- JUnit 5

## 사전 준비

- JDK 21
- Docker Desktop 및 Docker Compose

## 로컬 인프라

[`compose.yaml`](./compose.yaml)은 다음 서비스를 구성합니다.

| 서비스 | 이미지 | 로컬 포트 | 개발용 설정 |
| --- | --- | --- | --- |
| PostgreSQL | `postgres:latest` | `5432` | DB `pinlog`, 사용자 `ssafy`, 비밀번호 `secret` |
| Redis | `redis:latest` | Docker가 동적으로 할당 | 컨테이너 포트 `6379` |

직접 실행하고 상태를 확인하려면 다음 명령을 사용합니다.

```powershell
docker compose up -d
docker compose ps
```

종료할 때는 다음 명령을 사용합니다.

```powershell
docker compose down
```

Spring Boot Docker Compose 지원이 포함되어 있어 애플리케이션을 개발 모드로 실행하면 Compose 서비스 연결 정보를 자동으로 감지합니다.

> 현재 Compose 계정과 비밀번호는 로컬 개발 전용입니다. 배포 환경에서는 환경 변수나 별도의 시크릿 저장소로 분리해야 합니다.

## 애플리케이션 실행

Windows:

```powershell
.\gradlew.bat bootRun
```

macOS/Linux:

```bash
./gradlew bootRun
```

서비스가 `/api/core` 경로 prefix를 직접 소유하므로 기본 애플리케이션 주소는
`http://localhost:8080/api/core`입니다. 컨트롤러에는 `/api/core`를 다시 붙이지 않습니다.

현재 Spring Security에는 애플리케이션 실행 확인을 위한 임시 개발용 기본 계정이 설정되어 있습니다.

- 사용자명: `ssafy`
- 비밀번호: `ssafy`

이 설정은 곧 실제 인증·인가 구현으로 변경될 예정이며, 변경 과정에서 기본 계정 설정도 제거됩니다.

헬스체크와 Prometheus 메트릭 엔드포인트는 인증 없이 접근할 수 있습니다.

- 헬스체크: `http://localhost:8080/api/core/actuator/health`
- Prometheus: `http://localhost:8080/api/core/actuator/prometheus`

운영 환경의 probe 경로는 `/api/core/actuator/health`로 설정해야 합니다. Grafana에서
메트릭을 사용하려면 인프라 담당자에게 `/api/core/actuator/prometheus` 수집 등록을 요청합니다.

## 컨테이너 이미지

이미지를 빌드할 때 커밋 SHA를 전달할 수 있습니다.

```powershell
docker build --build-arg BUILD_SHA=sha-local -t pinlog-back:local .
```

컨테이너는 클러스터 보안 규약에 맞춰 UID 1000 비루트 사용자로 실행됩니다.

## 테스트

Windows:

```powershell
.\gradlew.bat test
```

macOS/Linux:

```bash
./gradlew test
```

테스트 결과 보고서는 `build/reports/tests/test/index.html`에서 확인할 수 있습니다.

## 프로젝트 구조

```text
pinlog-back/
├── src/
│   ├── main/
│   │   ├── java/com/pinlog/pinlogback/
│   │   └── resources/application.yml
│   └── test/
├── build.gradle
├── compose.yaml
├── Dockerfile
├── .dockerignore
├── gradlew
├── gradlew.bat
└── settings.gradle
```

## 설정 참고

- Java toolchain은 21로 고정되어 있습니다.
- Gradle은 시스템 Gradle 대신 저장소에 포함된 Wrapper를 사용합니다.
- PostgreSQL과 Redis는 로컬 개발용 Docker Compose 서비스입니다.
- H2는 가벼운 로컬·테스트 실행을 위한 런타임 의존성으로 포함되어 있습니다.
- 운영 ingress 경로와 애플리케이션 context path는 모두 `/api/core`로 맞춰야 합니다.
- 운영 health probe 경로는 `/api/core/actuator/health`입니다.
- 환경별 설정과 실제 인증정보는 저장소에 직접 커밋하지 않습니다.
