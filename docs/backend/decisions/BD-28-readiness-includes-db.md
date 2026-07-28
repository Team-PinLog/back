# BD-28. readiness 그룹에 `db`를 넣고 `redis`는 넣지 않는다

- **상태**: Accepted
- **날짜**: 2026-07-28
- **관련**: S15P11A705-106, [infra#33](https://github.com/Team-PinLog/infra/issues/33), [BT-03](../troubleshooting/BT-03-health-endpoint-blocks-on-redis-outage.md)

## 맥락

배포 계약(S15P11A705-51)을 검증하면서 probe 경로를 실측한 결과, `/health/liveness`·`/health/readiness`는 외부 의존성이 죽어도 항상 `200 UP`이었다.

| endpoint | 정상 | PostgreSQL·Redis 정지 |
|---|---|---|
| `/api/core/actuator/health` | 200 | Redis 정지 시 응답 없음(BT-03) |
| `/api/core/actuator/health/liveness` | 200 UP | 200 UP |
| `/api/core/actuator/health/readiness` | 200 UP | 200 UP |

Spring Boot에서 `management.endpoint.health.probes.enabled: true`만 켜면 두 그룹의 구성원은 `livenessState`·`readinessState`뿐이다. 둘 다 애플리케이션 내부 상태여서 외부 의존성을 전혀 보지 않는다. 그래서 **DB가 죽어도 Pod는 Ready로 남고, 트래픽을 계속 받아 요청이 500으로 실패한다.**

인프라 체크리스트의 "PostgreSQL 정상 연결 시 readiness가 UP"은 문구 그대로는 충족하지만, 의도가 "장애 시 트래픽에서 빠진다"였다면 충족하지 않는다. 이 어긋남을 infra#33에서 인프라팀에 올렸다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 현행 유지 (`readinessState`만) | 외부 의존성 장애가 Pod 재시작·unready로 번지지 않는다 | DB가 죽어도 Ready로 남아 요청이 500으로 실패한다. 장애 시 트래픽 차단 수단이 없다 |
| **(b) readiness에 `db` 추가** | DB 장애 시 endpoint에서 빠진다 | 단일 replica에서는 그대로 전면 장애다. DB 순단마다 unready가 된다 |
| (c) `db` + `redis` 둘 다 | 인프라 체크리스트 기대에 가장 부합 | Redis가 캐시 용도면 과잉이다. BT-03의 60초 블로킹을 함께 손봐야 한다 |

## 결정

**(b)를 채택한다. 제약으로 주어짐.**

세 안의 장단점을 정리해 infra#33에서 운영 관점 판단을 요청했고, 인프라팀이 (b)로 확정했다. 백엔드가 고른 것이 아니다.

> - liveness: DB·Redis 등 외부 dependency 제외
> - readiness: PostgreSQL `db`만 포함
> - Redis: 현재 제외
>
> DB 장애 시 traffic endpoint에서 빠지게 하되, Redis의 60초 command timeout과 서비스 criticality가 정리되기 전에는 Redis 장애를 전체 Pod unready로 전파하지 않는 선택입니다.

(c)를 백엔드 단독으로 고르기엔 근거가 부족했다. Redis에 의존하는 것은 Feed Cache인데 Feed는 AI 파트 소유이고 아직 구현 전이라, Redis 장애 시 서비스가 어떻게 동작해야 하는지가 정해지지 않았다.

제약 안에서 백엔드가 고른 것은 표현 방식뿐이다. `include`에 `readinessState,db`를 함께 적는다. `include`는 기본 구성원을 대체하므로 `db`만 적으면 `readinessState`가 빠지고, 그러면 기동 완료 전에도 readiness가 UP이 된다.

liveness 그룹은 손대지 않고 기본값을 유지한다. 여기에 외부 의존성을 넣으면 DB 순단이 Pod 재시작으로 번진다.

## 결과

**감수하는 것**

- **단일 replica에서 DB 장애는 그대로 전면 장애다.** readiness가 내려가면 유일한 Pod가 endpoint에서 빠져 503이 된다. 500으로 실패하던 것이 503으로 바뀌는 것이 이 결정의 실질이다. 사용자가 보는 결과는 어느 쪽이든 장애다.
- **DB 순단마다 unready가 된다.** 짧은 네트워크 끊김에도 Pod가 트래픽에서 빠지고 복구 후 다시 들어온다. 순단이 잦으면 오히려 가용성을 깎는다.
- **Redis 장애는 여전히 readiness에 반영되지 않는다.** Feed Cache가 Redis를 쓰기 시작하면 Redis가 죽은 Pod가 Ready로 남는다. 그때 이 결정을 다시 봐야 한다.
- **집계 `/health`의 60초 블로킹은 남는다.** probe 경로가 아니라서 운영에 직접 영향은 없지만, 사람이 `/health`를 조회하면 계속 걸린다. [back#65](https://github.com/Team-PinLog/back/issues/65)로 분리했다.
- **Redis 제외를 동작으로 증명하기 어렵다.** "Redis가 죽어도 readiness는 UP"을 확인하려면 Redis health indicator를 켠 채 죽은 주소를 향하게 해야 하고, 기본 60초 timeout 때문에 테스트 전용으로 timeout을 줄여야 한다. 운영 설정이 아니라 테스트 설정으로 줄인 것이므로, 운영의 60초는 back#65가 정리하기 전까지 그대로다.

**재검토 트리거**

- Feed Cache가 Redis에 실제로 의존하기 시작하면 → readiness에 `redis`를 넣을지 다시 판단한다. back#65의 두 결론(timeout 값, 장애 시 동작 계약)이 선행이다.
- replica가 2 이상이 되면 → (b)의 단점이 사라진다. 이 결정의 비용이 낮아지므로 (c)로 옮기는 논의가 쉬워진다.
- DB 순단으로 unready가 반복 관측되면 → readiness 실패 임계값(`failureThreshold`)을 인프라와 조정한다. 그룹 구성원을 되돌리는 것은 먼저 두지 않는다.
