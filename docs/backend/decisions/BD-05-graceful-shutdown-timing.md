# BD-05. graceful shutdown 타임아웃을 20s로 두고 Kubernetes 쪽 값은 Infra에 위임한다

- **상태**: Accepted
- **날짜**: 2026-07-27
- **관련**: S15P11A705-51, [BI-05](../implements/BI-05-2026-07-27-graceful-shutdown.md), Infra 연계 S15P11A705-47

## 맥락

S15P11A705-51이 "SIGTERM graceful shutdown과 진행 중 요청 종료를 보장한다"를 요구한다. 확인해보니 `application.yml`에 `server.shutdown` 설정이 없어 기본값 `immediate`로 동작하고 있었다 — SIGTERM을 받으면 진행 중인 요청을 버리고 즉시 종료한다.

문제는 이게 애플리케이션 설정만으로 완결되지 않는다는 점이다. 무중단이 성립하려면 네 값이 맞물려야 한다.

| 값 | 위치 | 소유 |
|---|---|---|
| `server.shutdown` | `application.yml` | 백엔드 |
| `spring.lifecycle.timeout-per-shutdown-phase` | `application.yml` | 백엔드 |
| `terminationGracePeriodSeconds` | Deployment 매니페스트 | Infra |
| `preStop` hook | Deployment 매니페스트 | Infra |

S15P11A705-51의 제외 범위가 "Kubernetes·Argo CD·Secret delivery·Ingress 구현은 Infra 소유"라고 명시하므로, 백엔드는 앞 두 값만 정하고 뒤 두 값은 숫자를 제안해 넘긴다.

`preStop`이 왜 필요한지도 함께 기록한다. Kubernetes는 Pod 종료 시 Endpoints 갱신과 SIGTERM 전송을 **동시에** 시작한다. 두 작업은 순서가 보장되지 않으므로, `preStop` 지연이 없으면 이미 종료를 시작한 Pod로 트래픽이 계속 들어온다. graceful shutdown이 켜져 있어도 새 요청은 거부되므로 502가 난다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 타임아웃 미설정 (Boot 기본 30s) | 설정이 짧다 | k8s `terminationGracePeriodSeconds` 기본값도 30s다. `preStop` 지연을 더하면 grace period를 넘겨 SIGKILL로 잘린다 — 정확히 경계라 여유가 없다 |
| (b) 20s + grace 40s | preStop 5s + 종료 20s = 25s로 15s 여유. 배포 지연도 크지 않다 | 20초를 넘기는 장기 요청은 잘린다 |
| (c) 60s 이상 | 어떤 요청도 안 잘린다 | 롤아웃이 Pod마다 1분씩 늘어난다. Argo CD sync 타임아웃과 부딪힐 수 있다 |

## 결정

**(b)** — `server.shutdown: graceful` + `timeout-per-shutdown-phase: 20s`. Infra에는 `preStop` sleep 5s와 `terminationGracePeriodSeconds: 40`을 제안한다.

```
preStop sleep 5s  +  앱 종료 20s  =  25s  <  terminationGracePeriodSeconds 40s
```

결정적 이유는 두 가지다. 첫째, (a)의 기본값 조합은 여유가 0이라 실패 시 조용히 SIGKILL로 떨어진다 — 로그에 원인이 남지 않아 진단이 어렵다. 둘째, PinLog의 요청은 전부 짧다. 가장 긴 경로인 AI 자연어 검색조차 FastAPI 호출 타임아웃이 5s(`docs/ai/spec/ai-integration.md`)라 20s 안에 끝난다.

## 결과

- 이 결정으로 감수하는 것:
  - 20초를 넘기는 요청은 배포 중 잘린다. 현재 그런 경로는 없다.
  - 롤아웃이 Pod당 최대 25초 느려진다.
  - **숫자가 두 레포에 나뉘어 있다.** 한쪽만 바꾸면 조용히 깨진다 — `application.yml`의 주석과 이 문서가 짝을 명시하는 이유다.
- 재검토 트리거:
  - 20초를 넘길 수 있는 동기 엔드포인트가 생길 때 (대용량 업로드, 배치성 조회 등)
  - Infra가 `terminationGracePeriodSeconds`를 40이 아닌 값으로 정할 때
  - AI 검색 타임아웃(현재 5s)이 크게 늘어날 때
