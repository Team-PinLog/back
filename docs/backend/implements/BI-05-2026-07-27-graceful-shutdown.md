# BI-05. SIGTERM graceful shutdown과 probe 계약 테스트

- **상태**: ✅ 완료
- **날짜**: 2026-07-27
- **관련**: Jira 작업, [BD-05](../decisions/BD-05-graceful-shutdown-timing.md)

## 산출

- `src/main/resources/application.yml`
  - `server.shutdown: graceful` — SIGTERM 수신 시 새 요청을 막고 진행 중인 요청을 끝낸 뒤 종료한다. 이 값이 없으면 기본값 `immediate`로 동작해 진행 중인 요청이 버려진다.
  - `spring.lifecycle.timeout-per-shutdown-phase: 20s` — 배수 대기 상한. Kubernetes `terminationGracePeriodSeconds`와 짝을 이루는 값이라 주석으로 관계식(`preStop 5s + 20s < 40s`)을 남겼다. 근거는 [BD-05](../decisions/BD-05-graceful-shutdown-timing.md).
- `src/test/java/com/pinlog/pinlogback/DeploymentContractTests.java` — 테스트 4개 추가
  - `inFlightRequestsAreDrainedOnSigterm` — `ServerProperties.getShutdown() == Shutdown.GRACEFUL`
  - `shutdownTimeoutFitsInsideKubernetesTerminationGracePeriod` — `timeout-per-shutdown-phase == 20s`
  - `livenessProbeIsAvailableForKubernetes` — `/api/core/actuator/health/liveness` 200 + `"status":"UP"`
  - `readinessProbeIsAvailableForKubernetes` — `/api/core/actuator/health/readiness` 200 + `"status":"UP"`
- `docs/development/database-conventions.md` — "무중단 배포와 backward-compatible migration" 절 신설. Jira 작업의 "Flyway migration을 backward-compatible하게 작성하고 기존 테이블·컬럼의 즉시 삭제를 금지한다"를 규약으로 옮겼다.

## 검증

### 설정 바인딩 (테스트)

`DeploymentContractTests` 8개 전부 통과. 설정 추가 전에는 `@Value("${spring.lifecycle.timeout-per-shutdown-phase}")` 해석 실패로 **컨텍스트 자체가 뜨지 않아 8개 전부 실패**했다(red 확인). 설정 추가 후 green.

### 실제 동작 (런타임 로그)

설정이 바인딩됐다는 것과 실제로 배수가 도는 것은 다르므로, 테스트 실행 로그에서 Tomcat의 graceful shutdown 경로가 타는지 직접 확인했다.

```
o.s.boot.tomcat.GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete
o.s.boot.tomcat.GracefulShutdown : Graceful shutdown complete
```

설정 전에는 이 두 줄이 나오지 않는다. 컨텍스트 종료 시 `GracefulShutdown`이 실제로 실행됨을 확인했다.

**이 검증의 한계**: 진행 중인 요청이 실제로 *완료되는지*는 확인하지 못했다. 아직 도메인 엔드포인트가 없어서(`global/`만 존재) 배수를 관찰할 만큼 오래 걸리는 요청을 만들 수 없다. 위 로그는 "배수 절차가 실행된다"까지만 증명하고, "진행 중 요청이 잘리지 않는다"는 증명하지 않는다. Record API(Jira 작업)가 들어온 뒤 느린 요청 하나로 실제 배수를 확인하는 것이 남는다.

### probe 경로

`health.probes.enabled: true`는 이미 있었지만 liveness/readiness 경로를 검증하는 테스트가 없었다. Infra(Jira 작업)가 이 경로를 probe로 박을 예정이라, 경로가 바뀌면 CrashLoop로 이어진다. 회귀 감시로 테스트를 추가했다.

## 반복될 함정 (다음 사람에게)

1. **Spring Boot 4.1에서 `ServerProperties` 패키지가 바뀌었다.** `org.springframework.boot.autoconfigure.web.ServerProperties`(Boot 3.x)가 아니라 **`org.springframework.boot.web.server.autoconfigure.ServerProperties`**(`spring-boot-web-server` 아티팩트)다. `Shutdown`은 `org.springframework.boot.web.server.Shutdown` 그대로다. 구버전 예제를 그대로 가져오면 컴파일이 깨진다.
2. **`@Value`로 미정의 속성을 참조하면 테스트 전체가 죽는다.** 기본값 없는 `@Value("${...}")`는 플레이스홀더 해석 실패로 컨텍스트 로딩을 통째로 막는다. 한 테스트만 빨개지는 게 아니라 그 클래스 전부가 실패해서 원인이 가려진다.
3. **이 숫자는 혼자 못 지킨다.** `timeout-per-shutdown-phase`만 늘리고 `terminationGracePeriodSeconds`를 그대로 두면 SIGKILL로 잘리는데, 로그에 아무것도 안 남아 진단이 어렵다. 값을 바꿀 때는 반드시 Infra 매니페스트와 함께 본다.
4. **CI의 `FlywayMigrationTests`는 backward compatibility를 검증하지 않는다.** 빈 DB에 전체 migration을 적용하는 것까지만 본다. `DROP COLUMN`을 넣어도 CI는 초록불이고 RollingUpdate 중에 구 Pod가 터진다. 이건 `database-conventions.md`의 규약과 리뷰로만 막힌다.

## 게이트 실행

```
./gradlew clean check --no-daemon
```

Docker Desktop 실행 상태에서 PostgreSQL/pgvector Testcontainers 포함 전체 스위트 실행 — `BUILD SUCCESSFUL`, 테스트 실패 없음.

## 남은 것

Jira 작업은 단발 작업이 아니라 상시 계약이라 이 커밋으로 닫히지 않는다. 남은 항목:

- Infra 쪽 짝(`preStop`, `terminationGracePeriodSeconds`) 반영 — Infra 레포 이슈로 전달
- 진행 중 요청의 실제 배수 검증 — 도메인 엔드포인트(Jira 작업) 이후
- Jira 작업(최초 내부 배포)에서 probe·metrics 계약이 실제 클러스터에서 통하는지 확인
