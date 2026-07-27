# BI-05. Infra 배포 연동 체크리스트 검증 + pgvector 버전 정렬

- **상태**: ✅ 완료 (검증), ⚠️ blocker 2건 별도
- **날짜**: 2026-07-27
- **관련**: S15P11A705-51, Infra 연계 S15P11A705-46·S15P11A705-47, [BT-02](../troubleshooting/BT-02-flyway-out-of-order-version-ranges.md) · [BT-03](../troubleshooting/BT-03-health-endpoint-blocks-on-redis-outage.md)

Infra가 전달한 "Backend 배포 연동 수정·확인 체크리스트"를 항목별로 검증했다. 실제로 수정한 것은 pgvector 버전 정렬뿐이고 나머지는 이미 충족 상태였다.

## 수정

- `compose.yaml` — `pgvector/pgvector:0.8.1-pg16` → `0.8.5-pg16` + digest 핀(`sha256:1d53...f0fb`). 운영 목표 이미지와 일치.
- `src/test/.../PostgresContainerSupport.java` — Testcontainers 이미지도 `0.8.5-pg16`으로 정렬. [BD-01](../decisions/BD-01-h2-removal-testcontainers.md)이 "컨테이너 이미지는 로컬 compose와 동일 계열로 고정한다"고 정한 규약을 유지하기 위함. 체크리스트에 명시된 항목은 아니지만, 로컬·CI·운영이 갈리면 pgvector 동작 차이를 테스트가 못 잡는다.
- `docs/development/database-conventions.md`, `src/main/resources/db/migration/README.md` — 버전 표기 갱신 + extension 업그레이드 주의 추가.

## 검증 결과

### pgvector 전환 — 기존 volume 재사용

`docker compose down -v` 없이 이미지만 교체해 기동. 기존 데이터(`core`·`ai` 스키마, `public.flyway_schema_history`)가 그대로 유지됨을 확인했다.

**여기서 함정을 하나 발견했다.** 이미지는 0.8.5인데 DB에 설치된 extension은 0.8.1로 남는다.

```
  name  | default_version | installed_version
--------+-----------------+-------------------
 vector | 0.8.5           | 0.8.1
```

`CREATE EXTENSION IF NOT EXISTS vector`는 **이미 설치된 extension을 업그레이드하지 않는다.** 명시적으로 올려야 한다.

```sql
ALTER EXTENSION vector UPDATE;   -- 0.8.1 → 0.8.5 확인
```

운영 전환(S15P11A705-46)에서도 기존 PVC를 유지하면 같은 상황이 되므로 Infra에 전달했다.

### 운영 환경변수만으로 기동

체크리스트의 6개 환경변수만 주입해 `bootJar`를 실행했다(로컬 검증이라 host/port만 로컬 값으로 치환).

```
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD
SPRING_DATA_REDIS_HOST / _PORT
```

```
Successfully validated 5 migrations
Tomcat started on port 8080 (http) with context path '/api/core'
Started PinlogBackApplication in 8.684 seconds
```

PostgreSQL·Redis 모두 연결됐고 추가 설정은 필요 없었다. `application.yml`에 datasource 설정 자체가 없어 Spring Boot 표준 환경변수가 그대로 바인딩된다.

### Health endpoint

| endpoint | 결과 |
|---|---|
| `/api/core/actuator/health` | 200 `{"groups":["liveness","readiness"],"status":"UP"}` |
| `/api/core/actuator/health/liveness` | 200 `{"status":"UP"}` |
| `/api/core/actuator/health/readiness` | 200 `{"status":"UP"}` |
| `/api/core/actuator/prometheus` | 200 |
| `/api/core/v3/api-docs` | 404 (prod 프로파일에서 차단 — 의도된 동작) |

의존성을 죽였을 때의 동작은 [BT-03](../troubleshooting/BT-03-health-endpoint-blocks-on-redis-outage.md)에 따로 기록했다.

### 자격증명 비노출

- 로그에 DB 비밀번호 문자열 없음. `password`·`secret`·`credential` 언급 라인 0.
- jar 내 `application.yml`·`application-prod.yml`에 자격증명·운영 주소 하드코딩 없음.
- 이미지 루트에 `.env`·`*.pem`·`.git` 없음(`/etc/ssl/cert.pem`은 베이스 이미지 CA 번들).
- 이미지 환경변수에 `BUILD_SHA` 외 주입값 없음.

### 컨테이너 계약

```
Arch:       amd64/linux
User:       1000
Entrypoint: [java -jar /app.jar]
Exposed:    8080/tcp
BUILD_SHA:  782320221b30af9fae7d44da3c226ddfc006a49c
Java:       Temurin 21.0.11+10
```

`Dockerfile`은 변경하지 않았다.

## 검증 과정에서 한 실수 (기록)

`--platform linux/amd64` 빌드를 백그라운드로 돌리면서 앱 기동 검증을 병행하다가, **앞서 띄웠던 프로세스를 제대로 종료하지 못한 채 두 번째 실행을 했다.** 두 번째 프로세스는 `Port 8080 was already in use`로 죽었는데, 살아 있던 첫 프로세스가 health 응답을 정상으로 돌려줘서 검증이 통과한 것처럼 보였다. 첫 프로세스에는 `SPRING_FLYWAY_OUT_OF_ORDER=true`라는 계약 외 환경변수가 있었으므로 그 결과는 "환경변수 6개만으로 기동된다"의 근거가 될 수 없었다.

Git Bash의 `ps aux | grep java`가 Windows JVM 프로세스를 잡지 못해 `pkill`이 조용히 실패한 것이 직접 원인이다. 포트 점유를 `Get-NetTCPConnection -LocalPort 8080`으로 확인·정리한 뒤 처음부터 다시 검증했고, 위에 적은 결과는 재실행한 깨끗한 실행의 것이다.

**교훈**: Windows에서 JVM 종료를 확인할 때 `ps`/`pkill`을 믿지 말고 포트 점유로 확인한다. 그리고 기동 검증은 "응답이 200인가"가 아니라 **"내가 띄운 그 프로세스가 응답했는가"** 를 확인해야 한다 — 기동 로그의 타임스탬프를 함께 본다.

## 게이트 실행

```
./gradlew clean check --no-daemon    # BUILD SUCCESSFUL (Testcontainers 0.8.5)
docker build --platform linux/amd64 --build-arg BUILD_SHA=$(git rev-parse HEAD) -t pinlog-back:local-check .   # exit 0
```

## 남은 blocker

1. [BT-02](../troubleshooting/BT-02-flyway-out-of-order-version-ranges.md) — 백엔드 migration 번호 구간이 AI 구간보다 낮아, AI migration이 적용된 DB에서는 백엔드 migration 추가 시 Flyway validate가 실패한다. **S15P11A705-66 착수 전에 합의가 필요하다.**
2. [BT-03](../troubleshooting/BT-03-health-endpoint-blocks-on-redis-outage.md) — Redis 장애 시 집계 `/health`가 60초 블로킹. probe 경로를 liveness·readiness로 한정하면 회피되지만, 그 경우 의존성 장애가 readiness에 반영되지 않는다.
