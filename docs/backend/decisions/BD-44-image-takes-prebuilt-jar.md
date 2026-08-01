# BD-44. 이미지가 미리 빌드된 jar를 받게 하고, `Dockerfile`이 소스만으로 혼자 빌드되는 성질을 버린다

- **상태**: Accepted
- **날짜**: 2026-08-01
- **관련**: [S15P11A705-238](https://ssafy.atlassian.net/browse/S15P11A705-238) · [BD-42](BD-42-ci-skip-inside-job-not-paths-ignore.md) · [BI-32](../implements/BI-32-2026-07-31-ci-pipeline-speedup.md)

## 맥락

CI가 프로젝트를 두 번 컴파일한다. `Run checks`가 러너에서 컴파일하고, Docker 빌드가 컨테이너 안에서 `./gradlew bootJar`로 다시 컴파일한다. 실측 분해는 이렇다.

| 레이어 | 시간 | 캐시 |
|---|---|---|
| `RUN ./gradlew dependencies` | 26.7초 | `build.gradle`이 바뀔 때만 무효화 — 캐시된다 |
| `RUN ./gradlew bootJar` | 29.7~33.4초 | **`src`가 매 PR 바뀌어 캐시되지 않는다** |

직전 작업([BI-32](../implements/BI-32-2026-07-31-ci-pipeline-speedup.md))이 앞 레이어에 GHA 캐시를 붙여 PR에서 13초를 줄였는데, 멀티스테이지에서 그 레이어는 **버려지는 build 스테이지 안에 있어** `mode=min`으로는 잡히지 않는다. `mode=max`가 필수였고 그 내보내기가 `dev` push의 발행 빌드를 67초에서 163초로 늘렸다(로그: `exporting to GitHub Actions Cache ... DONE 91.4s`). 즉 **13초를 얻고 96초를 냈다.**

남은 `bootJar` 구간은 캐시로 접근할 수 없다. 없애려면 컨테이너에서 컴파일하지 않는 수밖에 없고, 그러려면 `Dockerfile`이 소스가 아니라 결과물을 받아야 한다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 유지 — 멀티스테이지에 캐시만 얹는다 | `docker build .`가 소스만으로 돌아 로컬에서 편하다 | 중복 컴파일 33초가 남고 캐시로 못 지운다. `mode=max` 비용 96초를 계속 낸다 |
| (b) 버린다 — jar를 받는 단일 스테이지 | 33초가 사라진다. 캐시할 대상이 없어져 캐시 설정과 96초가 함께 빠진다 | `docker build .` 전에 `./gradlew bootJar`가 필요하다. 이미지 검증이 "컨테이너에서 빌드가 되는가"를 더는 보지 않는다 |
| (c) 둘 다 둔다 — 멀티스테이지 유지 + CI만 jar 주입 | 로컬 편의를 지키면서 CI는 빨라진다 | 로컬 경로를 위해 Gradle 실행을 남겨야 해 캐시 설정을 못 걷는다(96초 유지). **CI가 검증하는 경로와 로컬 경로가 갈려** "CI는 초록인데 로컬은 깨진다"가 가능해진다 |

## 결정

**(b) — 능동적 선택.**

혼자 빌드되는 성질을 실제로 쓰는 곳을 먼저 확인했다. `compose.yaml`은 앱 이미지를 빌드하지 않고(Postgres·Redis만), `infra`는 게시된 이미지를 태그로 받아쓰기만 하며(`apps/prod/back/values.yaml`), 저장소에서 이 `Dockerfile`을 정기적으로 빌드하는 것은 **CI 두 군데뿐**이다. 유일한 로컬 빌드 사례는 [BI-06](../implements/BI-06-2026-07-27-deployment-contract-verification.md)의 배포 계약 검증인데, 명령 한 줄이 앞에 붙을 뿐 그대로 성립한다. 가끔 하는 감사이지 일상 경로가 아니다.

(c)를 버린 이유가 결정적이다. 비용을 줄이려고 두 경로를 두면 **캐시 설정을 못 걷어 96초를 그대로 내면서** 검증 경로와 실사용 경로가 갈리는 위험까지 새로 산다. 즉 (c)는 (a)보다 나아지지 않으면서 위험만 는다.

**감수한다고 적어 둔 "이미지 검증이 약해진다"는 실제로는 대부분 착시다.** 없어지는 것은 "컨테이너 안에서 Gradle 빌드가 성공하는가"이고 그것은 `Run checks`가 러너에서 이미 본다. 남는 검증(베이스 이미지·`COPY`·UID 1000·엔트리포인트·포트)이 배포에서 실제로 걸리는 부분이다. 그 계약이 유지되는 것은 만든 이미지를 `docker inspect`로 확인했다 — `User=1000`, `Entrypoint=[java -jar /app.jar]`, `8080/tcp`, `BUILD_SHA` 주입, `amd64/linux`.

## 결과

- 이 결정으로 감수하는 것:
  - `docker build .`가 단독으로 성립하지 않는다. `./gradlew bootJar`가 선행이며 `README.md`와 `Dockerfile` 주석에 적었다. 잊으면 `COPY build/libs/*.jar` 단계에서 실패하므로 조용히 잘못되지는 않는다.
  - `image-publish`가 별도 잡이라 jar를 아티팩트로 넘겨받는다(77.6MB). 잡 사이 전송 비용이 새로 생기고, 그 잡에서 Gradle을 다시 돌리면 이 결정이 무의미해진다 — `BackendCiSpeedContractTests`가 두 잡이 같은 아티팩트 이름을 보는지 고정한다.
  - 이미지가 러너의 Gradle 환경에 의존한다. 러너 JDK와 이미지 JRE의 메이저 버전이 어긋나면 빌드가 아니라 **기동에서** 드러난다. 지금은 둘 다 Temurin 21이다.
- 재검토 트리거(이 조건이 오면 다시 논의):
  - 아티팩트 전송이 `dev` 발행 시간의 큰 몫이 되면, 발행을 `check`와 한 잡으로 합치는 안을 본다.
  - 러너에서 만든 jar와 이미지가 갈리는 사고가 한 번이라도 나면 (c)의 위험 평가를 다시 한다.
  - `Dockerfile`을 소스에서 빌드해야 하는 소비자(다른 파트·외부 기여자)가 생기면 (c)를 재검토한다.
