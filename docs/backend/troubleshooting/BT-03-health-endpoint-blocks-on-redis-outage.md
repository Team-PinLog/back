# BT-03. Redis 장애 시 `/actuator/health`가 최대 1분간 응답하지 않는다

- **상태**: ⚠️ 미해결 — Infra와 probe 경로 합의 후 조치
- **날짜**: 2026-07-27
- **레이어**: Actuator / 배포 probe
- **관련**: S15P11A705-51 검증 중 발견, S15P11A705-47(probe 구성)에 영향

## 증상

운영 계약 환경변수만으로 앱을 띄운 뒤 Redis 컨테이너를 정지하고 각 endpoint를 호출했다.

| endpoint | Redis 정상 | Redis 정지 |
|---|---|---|
| `/api/core/actuator/health` | 200 `{"status":"UP"}` | **응답 없음** (15s·40s 타임아웃 모두 초과) |
| `/api/core/actuator/health/liveness` | 200 `{"status":"UP"}` | 200 `{"status":"UP"}` |
| `/api/core/actuator/health/readiness` | 200 `{"status":"UP"}` | 200 `{"status":"UP"}` |

PostgreSQL까지 함께 정지해도 liveness·readiness는 200 UP을 유지했다.

## 원인

### 1. `/health`가 막히는 이유

Lettuce의 기본 command timeout이 60초다. 집계 health가 Redis health indicator를 호출하면 그 시간만큼 블로킹된다.

```
Caused by: io.lettuce.core.RedisCommandTimeoutException: INFO. Command timed out after 1 minute(s)
```

### 2. liveness·readiness가 UP을 유지하는 이유

Spring Boot에서 `management.endpoint.health.probes.enabled=true`가 만드는 두 그룹의 기본 구성원은 `livenessState`·`readinessState`뿐이다. **`db`·`redis` health indicator는 두 그룹 어디에도 포함되지 않는다.** 두 상태는 애플리케이션 자체의 수명주기만 반영하므로 외부 의존성이 죽어도 UP이다.

## 영향

- **probe로 `/actuator/health`(집계)를 쓰면 위험하다.** Redis가 죽는 순간 probe가 응답하지 않고, `timeoutSeconds`를 넘겨 실패로 처리된다. livenessProbe에 걸려 있으면 Redis 장애가 **애플리케이션 재시작**으로 번진다.
- 반대로 liveness·readiness 그룹을 쓰면 안전하지만, **DB·Redis가 죽어도 Pod는 계속 Ready로 남아** 트래픽을 받는다. Infra 체크리스트의 "PostgreSQL 정상 연결 시 readiness가 UP"은 문구 그대로는 충족하지만, "장애 시 트래픽에서 빠진다"는 기대라면 충족하지 않는다.

## 선택지 (Infra 합의 필요)

| 안 | 효과 | 감수할 것 |
|---|---|---|
| (a) 현행 유지 — probe는 liveness·readiness 그룹만 사용 | 외부 의존성 장애가 Pod 재시작으로 번지지 않음 | DB·Redis 장애 시에도 Ready 유지. 요청은 500으로 실패 |
| (b) readiness 그룹에 `db` 추가 | DB 장애 시 트래픽에서 빠짐 | 단일 replica면 그대로 전면 장애. DB 순단마다 unready |
| (c) readiness에 `db`+`redis` 추가 | 체크리스트 기대에 가장 부합 | Redis가 캐시 용도라면 과잉. 60s 블로킹 문제도 함께 해결해야 함 |
| (d) Lettuce command timeout 단축 (예: 2s) | `/health` 블로킹 완화 | 느린 Redis 응답을 실패로 오판할 수 있음 |

Feed Cache가 Redis에 의존하지만([`docs/ai/spec/feed-profile-cache.md`](../../ai/spec/feed-profile-cache.md)) Feed는 AI 파트 소유이고 아직 구현 전이라, 지금 시점에 (c)를 확정하기엔 근거가 부족하다.

## 재발 방지

- Infra 매니페스트의 probe 경로는 **집계 `/health`가 아니라 `/health/liveness`·`/health/readiness`** 를 쓴다. 이 경로는 `DeploymentContractTests`가 회귀를 감시한다.
- (b)~(d)를 채택하면 `DeploymentContractTests`에 장애 시나리오 검증을 추가하고 이 문서 상태를 갱신한다.
