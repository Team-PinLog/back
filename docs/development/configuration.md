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
- **readiness 그룹은 `readinessState,db`** 입니다. Kubernetes probe는 집계 `/health`가 아니라 `/health/liveness`·`/health/readiness`를 씁니다. `probes.enabled`만 켜면 두 그룹의 구성원이 애플리케이션 내부 상태뿐이어서 DB가 죽어도 Ready로 남습니다. `include`는 기본 구성원을 대체하므로 `readinessState`를 함께 적습니다 — 빼면 기동 완료 전에도 UP이 됩니다. **`redis`는 넣지 않고 liveness 그룹은 기본값을 유지합니다**(외부 의존성을 넣으면 DB 순단이 Pod 재시작으로 번집니다). 인프라와 합의한 운영 정책이며 근거와 감수하는 점은 [BD-28](../backend/decisions/BD-28-readiness-includes-db.md)에 있습니다.

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
      group:
        readiness:
          include: readinessState,db
```

## 비밀값과 자격증명 주입

**비밀값만** Kubernetes Secret으로 봉인하고, 주소·DB 이름·사용자명은 비밀이 아니므로 GitOps values에 평문으로 둡니다 — 값이 무엇인지 매니페스트를 읽어서 알 수 있어야 하고, Secret으로 옮기면 그 값이 어디서 오는지 추적할 수 없게 됩니다.

`Team-PinLog/infra`가 실제로 주입하는 이름은 Spring Boot 표준 relaxed-binding 이름 그대로입니다(`infra/apps/prod/back/values.yaml`):

```yaml
env:
  - name: SPRING_DATASOURCE_URL
    value: jdbc:postgresql://postgres:5432/pinlog
  - name: SPRING_DATASOURCE_USERNAME
    value: pinlog
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef: {name: postgres-credentials, key: password}
  - name: SPRING_DATA_REDIS_HOST
    value: redis
  - name: SPRING_DATA_REDIS_PORT
    value: "6379"
```

`application.yml`에는 `spring.datasource.*`·`spring.data.redis.*`를 아예 적지 않습니다. 위 다섯 개가 실제 k8s 환경변수로 주입되고, Spring Boot의 표준 이름 자동 바인딩이 그대로 `spring.datasource.url` 등으로 매핑하기 때문입니다 — `${...}` placeholder도, 리터럴 기본값도 필요 없습니다. `SPRING_DATASOURCE_PASSWORD`만 `postgres-credentials` Secret에서 오고 나머지 네 개는 평문 GitOps 값입니다. 새 비밀번호·API 키가 필요하면 저장소에 넣지 말고 인프라 담당자에게 요청합니다. (이전엔 이 값들을 `application-prod.yml`에 리터럴로 다시 적어 두었으나, OS 환경변수가 profile yaml보다 우선순위가 높아 애초에 무시되고 있었다 — [BD-46](../backend/decisions/BD-46-datasource-redis-config-follows-infra-env-vars.md).)

> **정정 (2026-08-03)**: 이 절은 한동안 "환경변수로 주입되는 건 `DB_PASSWORD` 하나뿐, url·username·host는 yaml 리터럴"이라고 적혀 있었으나, 그 이름·계약 모두 실제 배포(`infra/apps/prod/back/values.yaml`)와 달랐다. 실제 계약은 [`docs`(팀 공용) 레포의 `docs/static/12_배포_변수_및_Secret_표준.md`](https://github.com/Team-PinLog/docs/blob/main/static/12_배포_변수_및_Secret_표준.md)와 일치하며, 위 내용이 그것으로 교체한 결과다. `infra/docs/backend-conventions.md` §5도 같은 이유로 낡아서 인프라 담당자에게 별도로 전달했다.

### 애플리케이션이 추가로 요구하는 비밀값

`DB_PASSWORD` 외에 아래가 필요합니다. 위 §5의 datasource 계약과 별개로, 이들은 **`back-owner-secrets`에 봉인돼 `envFrom`으로 주입됩니다**(S15P11A705-154). 허용 키 집합은 `infra/policy/sealedsecrets/back-prod.yaml`이 규정하므로, 새 키가 필요하면 저장소에 넣지 말고 인프라 담당자에게 요청해 그 집합에 더합니다.

| 변수 | 필수 여부 | 없으면 |
| --- | --- | --- |
| `JWT_PRIVATE_KEY` | **운영 필수** | 운영 프로파일은 **기동 실패**. 로컬·테스트는 임시 키쌍 생성 |
| `PINLOG_AI_INTERNAL_SECRET` | **운영 필수** | 운영 프로파일은 **기동 실패**. 그 외는 경고 후 기동하고 AI 호출이 전부 401로 거절됨 |
| `GOOGLE_CLIENT_ID` | 로그인에 필요 | `unset`으로 기동은 되고 인가 요청 URL 생성까지만 동작 (S15P11A705-63) |
| `GOOGLE_CLIENT_SECRET` | 로그인에 필요 | 위와 같음 (S15P11A705-63) |
| `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` | Kakao 로그인에 필요 | 위와 같음 (S15P11A705-64) |
| `NAVER_CLIENT_ID` · `NAVER_CLIENT_SECRET` | Naver 로그인에 필요 | 위와 같음 (S15P11A705-64) |

**쓰지 않는 키는 빈 값으로 두지 말고 정의 자체를 하지 않습니다.** `application.yml`이 `spring.config.import`로 `.env`를 프로퍼티로 올리므로, `KAKAO_CLIENT_ID=`처럼 정의만 하면 프로퍼티가 "없음"이 아니라 **빈 문자열**이 되어 `${KAKAO_CLIENT_ID:unset}`의 기본값이 적용되지 않습니다. 그러면 `Client id of registration 'kakao' must not be empty`로 **기동이 실패합니다.** 로컬 `.env`도, 운영 Secret도 같습니다 — 자격증명을 아직 받지 못한 공급자는 주입하지 않는 것이 정상 상태입니다([BT-05](../backend/troubleshooting/BT-05-dotenv-empty-value-overrides-default.md)).

`JWT_PRIVATE_KEY`는 RSA 2048 이상 PKCS#8 PEM입니다. **기동을 막는 것은 이것과 `PINLOG_AI_INTERNAL_SECRET` 둘뿐이고 소셜 로그인 자격증명은 막지 않습니다.** 둘 다 같은 이유입니다 — 없어도 뜨게 두면 조용히 망가집니다. 임시 서명 키를 만들면 파드마다 키가 달라져 스케일아웃·재시작 때 전면 로그아웃이 되고([BD-31](../backend/decisions/BD-31-jwt-rs256-key-management.md)), AI 시크릿이 비면 FastAPI가 401을 주는데 클라이언트가 실패를 삼켜 **임베딩이 하나도 생성되지 않는 것을 아무도 알 수 없습니다**(`AiProcessClient.requireSecret`).

> **`PINLOG_AI_BASE_URL`·`PINLOG_AI_EMBEDDING_PROFILE`은 비밀값이 아니고 요청 대상도 아닙니다.** 전자는 주소라 인프라가 평문 `env`로 넣고, 후자는 `application.yml`에 리터럴 기본값이 있어 환경변수는 덮어쓰기 수단일 뿐입니다([BD-39](../backend/decisions/BD-39-embedding-profile-in-application-config.md)). 다만 `PINLOG_AI_BASE_URL`이 현재 운영에 주입돼 있지 않아 AI 호출이 전부 자기 자신의 8000 포트로 나갑니다 — [back#122](https://github.com/Team-PinLog/back/issues/122).

```yaml
pinlog:
  auth:
    jwt:
      private-key: ${JWT_PRIVATE_KEY:}   # 비어 있으면 프로파일에 따라 갈린다
```

기본값을 비워 둔 이유는 placeholder 해석 실패로 죽이면 "왜 죽었는지"가 스택트레이스에만 남기 때문입니다. 코드에서 판정하면 무엇을 주입해야 하는지 메시지로 알려 줄 수 있습니다.

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

운영은 클러스터 내부 주소를 쓰고, 접속 정보 다섯 개(url·username·password·Redis host·port) 전부를 infra가 환경변수로 주입합니다 — 위 "비밀값과 자격증명 주입" 절 참고.

`application-prod.yml`에는 `spring.datasource.*`·`spring.data.redis.*`를 적지 않습니다([BD-46](../backend/decisions/BD-46-datasource-redis-config-follows-infra-env-vars.md)). 예전엔 주소·사용자명을 리터럴로 파일에 적어 "코드로 접속 정보를 통제한다"는 의도였지만, OS 환경변수가 profile yaml보다 우선순위가 높아 그 리터럴은 애초에 무시되고 있었다 — infra가 이미 표준 이름 환경변수로 다섯 개를 전부 주입하고 있었기 때문이다. `ConfigurationContractTests`는 이제 반대로 이 키들이 **재선언되지 않았는지**를 감시합니다.

> Redis는 캐시·세션 전용이라 재시작하면 비워집니다. 유실되면 안 되는 데이터를 넣어야 하면 사전에 인프라와 협의합니다.

## 설정 변경 검증

- 설정 변경은 애플리케이션 기동과 관련 통합 테스트로 검증합니다. DB가 필요한 테스트는 PostgreSQL Testcontainers를 사용합니다([테스트 규약](testing-conventions.md)).
- 헬스체크·actuator 노출을 바꾸면 `DeploymentContractTests`가 여전히 통과하는지 확인합니다. probe 그룹 구성은 `ReadinessProbeDatabaseOutageTests`(DB가 안 닿을 때 readiness가 UP이 아니고 liveness는 UP)와 `ReadinessProbeRedisOutageTests`(Redis가 안 닿아도 readiness는 UP)가 함께 감시합니다.

## 체크리스트

- [ ] 비밀번호·토큰·키를 저장소에 넣지 않았다 (환경변수 주입)
- [ ] context path는 `/api/core`, 컨트롤러에 중복하지 않았다
- [ ] `ddl-auto=validate`이고 스키마는 Flyway가 관리한다
- [ ] `open-in-view=false`이고, 연관 조회는 `fetch join`이나 전용 메서드로 명시한다
- [ ] actuator는 `health`·`prometheus`만 노출한다 (둘 다 필수)
- [ ] readiness 그룹은 `readinessState,db`이고, `redis`와 liveness 그룹은 건드리지 않았다
- [ ] 비밀값은 전부 환경변수 주입이고(datasource는 `DB_PASSWORD`, 나머지는 `back-owner-secrets`), 주소·사용자명은 프로파일 파일에 있다
- [ ] 환경별 차이만 프로파일에 두고 공통값을 복제하지 않았다
