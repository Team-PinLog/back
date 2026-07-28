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
- **`open-in-view=false`** 입니다. 기본값 `true`는 서비스 계층 밖에서도 영속성 컨텍스트를 열어 두어, DTO로 변환하기 전에 지연 로딩이 일어나 N+1이 조용히 발생합니다. 트랜잭션 경계를 `service`가 갖는다는 [계층 규칙](package-structure.md)과도 맞지 않습니다. 엔티티 밖에서 연관을 읽어야 하면 `fetch join`이나 전용 조회 메서드로 명시합니다.
- Actuator는 필수이며 `health`와 `prometheus`만 노출합니다. **둘 다 필수입니다** — `DeploymentContractTests`가 두 경로를 모두 검증하고, 어긋나면 배포와 모니터링이 함께 깨집니다.

```yaml
server:
  port: 8080
  servlet:
    context-path: /api/core

spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

## 비밀값과 자격증명 주입

**비밀값만** 환경변수로 주입받습니다. 주소·DB 이름·사용자명은 비밀이 아니므로 프로파일 파일에 그대로 적습니다 — 값이 무엇인지 코드를 읽어서 알 수 있어야 하고, 환경변수로 옮기면 그 값이 어디서 오는지 추적할 수 없게 됩니다.

`Team-PinLog/infra`가 정한 주입 계약은 **`DB_PASSWORD` 하나**입니다([infra/backend-conventions §5](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)).

```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres.pinlog-prod.svc.cluster.local:5432/pinlog
    username: pinlog
    password: ${DB_PASSWORD}      # 환경변수로 주입되는 유일한 값
  data:
    redis:
      host: redis.pinlog-prod.svc.cluster.local
      port: 6379
```

`${DB_URL}`·`${DB_USERNAME}`·`${REDIS_HOST}` 같은 이름을 새로 만들지 않습니다. 인프라가 주입하지 않는 변수를 참조하면 기동 시점에 해석 실패로 죽습니다. 새 비밀번호·API 키가 필요하면 저장소에 넣지 말고 인프라 담당자에게 요청합니다.

## 로컬 프로파일 (`local`)

로컬은 저장소 루트의 `compose.yaml`이 띄운 PostgreSQL·Redis에 접속합니다([CONTRIBUTING.md](../../CONTRIBUTING.md)의 로컬 시작). 로컬 전용 개발값은 커밋해도 되지만, 실제 비밀번호는 넣지 않습니다.

```yaml
# application-local.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:15432/pinlog
    username: pinlog
    password: pinlog-local      # 로컬 개발 전용 값
  data:
    redis:
      host: localhost
      port: 16379
```

로컬 실행 주소는 context path가 붙어 `http://localhost:8080/api/core/...` 입니다.

## 운영 프로파일 (`prod`)

운영은 클러스터 내부 주소를 사용하고 비밀번호만 주입받습니다.

- PostgreSQL: `postgres.pinlog-prod.svc.cluster.local:5432` (DB·사용자 모두 `pinlog`)
- Redis: `redis.pinlog-prod.svc.cluster.local:6379`

이 주소·네임스페이스는 인프라 소관입니다. 값이 바뀌면 [infra/backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)를 기준으로 하고 인프라 담당자와 맞춥니다.

이 값들은 `application-prod.yml`에 실제로 들어 있고, `ConfigurationContractTests`가 인프라 문서와 어긋나지 않는지 감시합니다. 주소를 환경변수로 빼지 않는 이유는 **비밀이 아니면서 어디에 접속하는지를 저장소만 보고 알 수 있어야** 하기 때문입니다 — 환경변수로 옮기면 그 값의 출처를 추적할 수 없게 됩니다.

> Redis는 캐시·세션 전용이라 재시작하면 비워집니다. 유실되면 안 되는 데이터를 넣어야 하면 사전에 인프라와 협의합니다.

## 설정 변경 검증

- 설정 변경은 애플리케이션 기동과 관련 통합 테스트로 검증합니다. DB가 필요한 테스트는 PostgreSQL Testcontainers를 사용합니다([테스트 규약](testing-conventions.md)).
- 헬스체크·actuator 노출을 바꾸면 `DeploymentContractTests`가 여전히 통과하는지 확인합니다.

## 체크리스트

- [ ] 비밀번호·토큰·키를 저장소에 넣지 않았다 (환경변수 주입)
- [ ] context path는 `/api/core`, 컨트롤러에 중복하지 않았다
- [ ] `ddl-auto=validate`이고 스키마는 Flyway가 관리한다
- [ ] `open-in-view=false`이고, 연관 조회는 `fetch join`이나 전용 메서드로 명시한다
- [ ] actuator는 `health`·`prometheus`만 노출한다 (둘 다 필수)
- [ ] 비밀값은 `DB_PASSWORD`만 환경변수이고, 주소·사용자명은 프로파일 파일에 있다
- [ ] 환경별 차이만 프로파일에 두고 공통값을 복제하지 않았다
