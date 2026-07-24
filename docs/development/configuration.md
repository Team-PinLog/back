# 설정·프로파일 규약

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 `application.yml` 구성, 환경 프로파일, 비밀값 주입의 기준입니다. 운영 배포·주입 계약은 [infra/docs/backend-conventions.md](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)를 따릅니다.

## 원칙

- **비밀값을 저장소에 넣지 않습니다.** 저장소는 public입니다. 비밀번호·토큰·키는 **환경변수로 주입**받습니다.
- 환경에 따라 달라지는 값(접속 주소·자격증명)은 프로파일 또는 환경변수로 분리하고, 코드에 하드코딩하지 않습니다.
- 기본 동작은 환경 독립적으로 두고, 환경별 차이만 프로파일이 덮습니다.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `application.yml` | 환경 독립 기본 설정. 어느 환경에서나 동일한 값 |
| `application-local.yml` | 로컬 개발 override (로컬 Compose DB/Redis) |
| `application-prod.yml` | 운영 override (클러스터 주소, 주입 자격증명) |

- 활성 프로파일은 `SPRING_PROFILES_ACTIVE` 환경변수로 선택합니다.
- 프로파일 파일에는 **그 환경에서만 다른 값**만 둡니다. 공통값을 프로파일마다 복제하지 않습니다.

## 기본 설정 (`application.yml`)

어느 환경에서나 같은 값은 여기에 둡니다.

- **context path는 `/api/core`** 로 고정합니다. 컨트롤러 매핑에 다시 쓰지 않습니다([API 규약](api-conventions.md), [infra/backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)).
- Hibernate는 **`ddl-auto=validate`** 입니다. 스키마는 Flyway가 관리합니다([데이터베이스 규약](database-conventions.md)).
- Actuator는 필수이며 `health`(및 필요 시 `prometheus`)만 노출합니다. 헬스체크 경로가 어긋나면 배포가 실패합니다.

```yaml
server:
  port: 8080
  servlet:
    context-path: /api/core

spring:
  jpa:
    hibernate:
      ddl-auto: validate

management:
  endpoints:
    web:
      exposure:
        include: health        # 지표가 필요하면 health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

## 비밀값과 자격증명 주입

접속 자격증명은 파일이 아니라 환경변수로 주입받습니다. 기본값에 실제 비밀번호를 넣지 않습니다.

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}      # 환경변수로 주입
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
```

운영에서는 인프라가 이 값을 주입합니다. 새 비밀번호·API 키가 필요하면 저장소에 넣지 말고 인프라 담당자에게 요청합니다([infra/backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)).

## 로컬 프로파일 (`local`)

로컬은 `dev/`의 Docker Compose가 띄운 PostgreSQL·Redis에 접속합니다([CONTRIBUTING.md](../../CONTRIBUTING.md)의 로컬 시작). 로컬 전용 개발값은 커밋해도 되지만, 실제 비밀번호는 넣지 않습니다.

```yaml
# application-local.yml (예시)
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/pinlog
    username: pinlog
    password: pinlog            # 로컬 개발 전용 값
  data:
    redis:
      host: localhost
      port: 6379
```

로컬 실행 주소는 context path가 붙어 `http://localhost:8080/api/core/...` 입니다.

## 운영 프로파일 (`prod`)

운영은 클러스터 내부 주소를 사용하고 자격증명은 주입받습니다.

- PostgreSQL: `postgres.pinlog-prod.svc.cluster.local:5432`
- Redis: `redis.pinlog-prod.svc.cluster.local:6379`

이 주소·네임스페이스는 인프라 소관입니다. 값이 바뀌면 [infra/backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)를 기준으로 하고 인프라 담당자와 맞춥니다.

> Redis는 캐시·세션 전용이라 재시작하면 비워집니다. 유실되면 안 되는 데이터를 넣어야 하면 사전에 인프라와 협의합니다.

## 설정 변경 검증

- 설정 변경은 애플리케이션 기동과 관련 통합 테스트로 검증합니다. DB가 필요한 테스트는 PostgreSQL Testcontainers를 사용합니다([테스트 규약](testing-conventions.md)).
- 헬스체크·actuator 노출을 바꾸면 `DeploymentContractTests`가 여전히 통과하는지 확인합니다.

## 체크리스트

- [ ] 비밀번호·토큰·키를 저장소에 넣지 않았다 (환경변수 주입)
- [ ] context path는 `/api/core`, 컨트롤러에 중복하지 않았다
- [ ] `ddl-auto=validate`이고 스키마는 Flyway가 관리한다
- [ ] actuator `health`(필요 시 `prometheus`)만 노출한다
- [ ] 환경별 차이만 프로파일에 두고 공통값을 복제하지 않았다
