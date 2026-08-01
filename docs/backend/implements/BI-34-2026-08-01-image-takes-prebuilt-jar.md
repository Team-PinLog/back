# BI-34. CI 이미지 빌드의 중복 컴파일 제거 — 러너가 만든 jar를 이미지가 받는다

- **상태**: ✅ 완료
- **날짜**: 2026-08-01
- **관련**: [S15P11A705-238](https://ssafy.atlassian.net/browse/S15P11A705-238) · [BD-44](../decisions/BD-44-image-takes-prebuilt-jar.md) · [BI-32](BI-32-2026-07-31-ci-pipeline-speedup.md)

## 무엇을 만들었나

`Dockerfile`을 미리 빌드된 jar를 받는 단일 스테이지로 바꿨다. 선택의 근거와 버린 대안은 [BD-44](../decisions/BD-44-image-takes-prebuilt-jar.md)에 있고, 여기는 배선과 검증이다.

| 자리 | 전 | 후 |
|---|---|---|
| `Run checks` | `./gradlew check` | `./gradlew check bootJar` |
| `Dockerfile` | 멀티스테이지, 컨테이너에서 `dependencies` + `bootJar` | 단일 스테이지, `COPY build/libs/*.jar` |
| PR 이미지 검증 | `cache-from: type=gha,scope=backend-image` | 캐시 선언 없음 |
| `image-publish` | `cache-from` + `cache-to: mode=max` | 캐시 선언 없음, `check`의 jar를 아티팩트로 받음 |
| `.dockerignore` | 제외 목록(`build` 포함) | 전부 제외 후 `!build/libs/*.jar`만 되돌림 |

**`bootJar`를 검사와 같은 Gradle 호출에 넣은 것이 핵심이다.** 따로 부르면 Gradle 시작 비용을 한 번 더 내지만, 같은 호출이면 방금 컴파일한 클래스를 그대로 써 태스크 자체가 **1.0초**다(프로파일 실측). 러너 쪽 추가 부담이 사실상 없다.

`image-publish`는 별도 러너라 `check`의 `build/`를 물려받지 못한다. 거기서 Gradle을 다시 돌리면 없애려던 중복이 그대로 되살아나므로 jar(77.6MB)를 아티팩트로 넘긴다. **PR 경로는 아티팩트가 필요 없다** — 같은 잡에서 jar를 만들고 이미지를 빌드한다. 그래서 업로드는 `push`에서만 한다.

## 검증

CI에서만 관측되는 변경이라 워크플로와 `Dockerfile`을 계약으로 읽는 정적 테스트를 갱신했다.

- **RED**: 기존 3건 중 2건이 새 계약과 어긋나 실패, 새로 더한 1건도 실패 — `4 tests completed, 3 failed`.
- **GREEN**: `BackendCiSpeedContractTests` 4건 통과.
- **회귀**: `RuntimeSecretWorkflowContractTests` 5건 통과 · `./gradlew clean check --no-daemon` 통과.

정적 테스트로는 "실제로 이미지가 만들어지는가"를 못 보므로 **로컬에서 직접 빌드해 계약을 확인했다.**

| 확인 | 결과 |
|---|---|
| 빌드 컨텍스트 전송량 | **81.38MB** — jar 하나뿐(`.dockerignore` 허용목록이 실제로 작동) |
| 빌드 시간(로컬, 베이스 워엄) | 8.3초 |
| `docker inspect` | `User=1000` · `Entrypoint=[java -jar /app.jar]` · `8080/tcp` · `BUILD_SHA=local-238` · `amd64/linux` |
| 이미지 안 `/app.jar` | 81,356,165 바이트, `BOOT-INF/classes/application.yml` 정상 |

[BI-06](BI-06-2026-07-27-deployment-contract-verification.md)이 고정한 배포 계약이 그대로 유지된다.

## 함정: 테스트가 주석의 `gradlew`를 잡았다

`Dockerfile` 전체 문자열에 `gradlew`가 없다고 단언했더니 실패했다. **`./gradlew bootJar`가 선행이라는 것을 알리는 주석 자체가 걸린 것이다.** 그 주석은 지우면 안 되는 정보다 — 없으면 다음 사람이 왜 빌드가 실패하는지 모른다.

판정 대상을 명령줄로 좁혔다(주석 줄 제외 후 검사). 단언을 약화시킨 것이 아니라 원래 보려던 것("컨테이너 안에서 Gradle을 실행하는가")으로 맞춘 것이다.

## 기대 효과와 실측 예정

| 경우 | 전(실측) | 후(예상) |
|---|---|---|
| PR 이미지 검증 | 48초 | 10~15초 |
| `dev` `image-publish` | 163초 | 25~35초 |

**PR 쪽 예상이 더 단단하다** — 아티팩트 전송이 없고, 로컬 빌드 8.3초에 CI의 베이스 이미지 pull과 buildx 오버헤드(직전 실행에서 약 5초)를 더한 값이기 때문이다. `dev` 쪽은 77.6MB 아티팩트 왕복이 변수다.

[BI-32](BI-32-2026-07-31-ci-pipeline-speedup.md)에서 추정이 세 번 빗나갔으므로 **이 표는 확정이 아니다.** 머지 후 실제 실행에서 재서 이 문서에 정정 절로 덧붙인다.

## 남은 것

`Run checks` 123초의 76%가 `:test`(약 73초)다. 그중 `DeploymentContractTests` 한 클래스가 8개 테스트에 18.7초로 26%를 차지한다. Spring 컨텍스트 재사용·병렬 실행이 다음 지렛대이며, 테스트 구조 변경이라 위험도가 달라 별건으로 둔다.
