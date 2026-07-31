# BI-32. CI 파이프라인 속도 개선 — 이미지 레이어 캐시·문서 전용 건너뛰기·`clean` 제거

- **상태**: ✅ 완료
- **날짜**: 2026-07-31
- **관련**: [S15P11A705-231](https://ssafy.atlassian.net/browse/S15P11A705-231) · [BD-42](../decisions/BD-42-ci-skip-inside-job-not-paths-ignore.md)

## 무엇을 만들었나

`backend-ci`가 PR마다 3.2~4.2분이 걸렸다. 실측한 성공 실행 하나(run 30604863940)의 스텝 배분이 원인을 그대로 보여 준다.

| 스텝 | 시간 |
|---|---|
| `Run checks` (`clean check` — 컴파일·checkstyle·테스트·커버리지) | 121초 |
| `Validate backend container image` | 61초 |
| 체크아웃·셋업·아티팩트 업로드 등 | 약 20초 |

세 가지를 고쳤다.

1. **이미지 빌드에 레이어 캐시를 붙였다.** `Dockerfile`은 컨테이너 안에서 의존성 해석과 `bootJar`를 처음부터 다시 한다. 레이어 분할 자체는 이미 잘 돼 있는데(`build.gradle`만 복사해 `dependencies`를 돌린 뒤 `src`를 복사한다) 캐시 설정이 없어 **매 PR이 의존성 내려받기를 반복**했다.
2. **문서만 바꾼 PR이 검사와 이미지 빌드를 건너뛴다.** 건너뛰기를 어디에 두느냐가 이 티켓의 실제 판단이고 [BD-42](../decisions/BD-42-ci-skip-inside-job-not-paths-ignore.md)에 있다 — 요지는 워크플로 수준 `paths-ignore`가 필수 상태 검사를 대기 상태로 만들어 문서 PR을 머지 불가로 바꾼다는 것이다.
3. **`clean`을 뺐다.** 러너는 매번 새로 뜨므로 `build/`에 지울 것이 없고, Gradle이 스스로 판단할 최신 여부만 버린다.

## 캐시를 PR에서는 읽기만 하는 이유

`cache-to`를 양쪽에 붙이는 것이 자연스러워 보이는데 그렇게 하지 않았다. GitHub Actions 캐시의 범위 규칙이 결론을 바꾼다.

- 캐시 항목은 브랜치 단위로 격리되지만, **기본 브랜치가 쓴 항목은 모든 브랜치가 읽을 수 있다.** 이 저장소의 기본 브랜치는 `dev`이고 PR도 `dev`를 향한다. 즉 `image-publish`가 `dev` push에서 채워 둔 항목을 모든 PR이 읽는다.
- 용량은 저장소 단위 공유 자원이다. PR마다 `mode=max`로 쓰면 브랜치별 항목이 쌓여 **정작 재사용되는 그 기본 브랜치 항목을 밀어낸다.**
- PR이 써도 실익이 거의 없다. 재사용되는 것은 `build.gradle`이 바뀔 때만 무효화되는 의존성 레이어이고, `src`가 바뀌는 `bootJar` 레이어는 PR이 캐시에 넣어도 다음 push에서 다시 미스다.

그래서 **읽기는 양쪽, 쓰기는 `dev` push 한 곳**으로 두고 범위 이름(`scope=backend-image`)을 명시했다. 기본값에 기대면 두 잡이 같은 캐시를 본다는 사실이 파일에 드러나지 않는다.

## 검증

로컬에서 관측할 수 없는 것들이라(CI에서만 드러난다) 워크플로 파일 자체를 계약으로 읽는 정적 테스트를 새로 만들었다: `BackendCiSpeedContractTests` 3건.

- **RED**: 구현 전 `3 tests completed, 3 failed`.
- **GREEN**: 구현 후 3건 통과.
- **회귀**: 기존 `RuntimeSecretWorkflowContractTests` 5건 통과.

셸로 판정하는 `docs_only`는 테스트가 닿지 않는 부분이라 **판정 스크립트를 떼어 내 대표 변경 집합 8종으로 직접 돌렸다.**

| 변경 파일 | 판정 |
|---|---|
| `docs/backend/WORKLOG.md` + `README.md` | `true` |
| `docs/development/code-style.md` | `true` |
| `src/main/java/.../X.java` | `false` |
| `docs/a.md` + `src/main/java/X.java` | `false` |
| `.github/workflows/backend-ci.yml` | `false` |
| `src/main/resources/db/migration/V30__x.sql` | `false` |
| `build.gradle` | `false` |
| (변경 없음 — 빈 목록) | `false` |

마지막 줄이 일부러 넣은 안전 장치다. `git diff`가 빈 결과를 주면 "빌드에 닿는 파일이 하나도 없다"가 참이 되어 `docs_only=true`로 넘어간다. `[ -n "$changed" ]` 조건이 그 경로를 닫고 전체 실행으로 떨어뜨린다.

## 함정: 이 워크플로에는 "secrets"라는 문자열을 쓸 수 없다

`RuntimeSecretWorkflowContractTests.backendCiCannotReadRuntimeOrBridgeSecrets`가 `backend-ci.yml`을 **문자열로** 읽어, 허용된 `${{ secrets.GITHUB_TOKEN }}` 한 번을 지운 나머지에 `secrets`가 없다고 단언한다. 소문자로 내린 전체 본문이 대상이라 **주석도 포함된다.**

캐시 설계를 설명하는 주석을 쓰다가 이 단언에 걸릴 수 있었다. 이 워크플로에 주석을 더할 때는 그 단어를 피해야 하고, 회피가 아니라 의도된 게이트다 — 운영 런타임 값 9개는 `pinlog-secrets-prod` Environment 경계 안에서만 읽히고 일반 CI는 그 경계 밖이라는 계약([BI-26](BI-26-2026-07-29-runtime-secret-workflow.md))을 문자열 수준에서 지킨다.

## 기대 효과 — 캐시가 지우는 것은 62초 중 26.7초뿐이다

> **이 절은 머지 전 추정이다. 머지 후 실측이 이 값들을 다시 뒤집었다 — 아래 「정정」을 먼저 읽는다.**

이 PR의 실행 로그가 이미지 빌드 62초의 내부 분해를 줬다. **처음 세운 기대치(약 25~30초)는 낙관적이었고, 이 측정으로 정정한다.**

| 레이어 | 시간 | 캐시로 지워지나 |
|---|---|---|
| `RUN ./gradlew dependencies --no-daemon` | 26.7초 | ✅ `build.gradle`이 바뀔 때만 무효화된다 |
| `RUN ./gradlew bootJar --no-daemon` | 29.7초 | ❌ `src`가 매 PR 바뀌므로 **항상 다시 돈다** |
| 베이스 이미지 pull 등 | 약 5초 | 부분적 |

| 경우 | 전 | 후(정정된 기대) |
|---|---|---|
| 코드 PR | 3.9분 | 약 3.4분 (이미지 검증 62초 → 약 35초) |
| 문서 PR | 약 3.5분 | 약 30초 |

**즉 실질적 이득은 문서 PR이고 코드 PR은 27초 정도다.** 코드 PR의 병목은 `Run checks` 126초이며 이 티켓은 거기를 건드리지 않았다. `clean` 제거도 속도 항목이 아니다 — 새 러너에는 지울 것이 없어 원래도 0초에 가까웠고, Gradle이 스스로 판단할 최신 여부를 버리지 않게 하는 정리다.

**첫 PR은 아직 빨라지지 않는다** — `dev`가 캐시를 채우기 전이라 이 PR의 이미지 검증은 62초 그대로였다. 머지되며 `image-publish`가 처음 항목을 쓰고 그다음 PR부터 효과가 관측된다.

## 남겨 둔 것: CI가 프로젝트를 두 번 컴파일한다

같은 로그가 드러낸 것이다. `Run checks`가 러너에서 컴파일하고(126초 안), Docker `bootJar`가 컨테이너에서 또 컴파일한다(29.7초). 러너가 만든 jar를 이미지 빌드에 넘기면 그 29.7초가 사라진다.

**하지 않았다.** 이미지 검증의 목적이 "`Dockerfile`이 실제로 빌드되는가"인데 jar를 밖에서 넣으면 그 보증이 약해진다. 캐시로 못 지우는 유일한 구간이라 값은 크지만, 검증 의미와 맞바꾸는 판단이므로 이 티켓 범위에서 정하지 않는다.

## 함께 고친 문서

`docs/development/code-style.md`가 *"`backend-ci / check`가 실행하는 `./gradlew clean check`"* 로 적고 있어 `clean` 제거와 어긋났다. CI와 로컬이 이제 다른 명령을 쓰므로 그 구분까지 적었다 — 로컬은 `build/`에 이전 결과가 남아 완료 보고 전 검증은 `clean check` 그대로다.

---

## 정정 (2026-08-01) — 머지 후 실측

머지 직후 `dev` push가 캐시를 채우고, 그 캐시를 읽는 첫 코드 PR([#140](https://github.com/Team-PinLog/back/pull/140), run `30642406372`)을 측정했다. **위 「기대 효과」의 수치 둘이 틀렸고, 문서에 없던 비용 하나가 드러났다.**

### 캐시는 붙었다 — 이득은 예상의 절반

| 레이어 | 캐시 전 | 캐시 후 |
|---|---|---|
| `RUN ./gradlew dependencies --no-daemon` | 26.7초 | **`CACHED`** — 단 가져와 푸는 데 6~8초 |
| `RUN ./gradlew bootJar --no-daemon` | 29.7초 | 33.4초 (같은 일, 실행 편차) |
| **이미지 검증 스텝 전체** | **61초** | **48초** |

**13초 절약이다.** 추정했던 "약 35초 / 27초 절약"이 틀린 이유는 둘이다.

1. **캐시 가져오기를 0으로 뒀다.** 레이어가 `CACHED`가 돼도 GHA 캐시에서 내려받아 푸는 데 6~8초가 든다. 새 러너마다 매번 낸다.
2. `bootJar`가 29.7 → 33.4초로 흔들려 4초를 먹었다.

PR 총 시간은 3.2분 → **3.3분**이다. `Run checks`의 실행 편차(112~126초, ±14초)가 캐시 이득 13초보다 커서 **총 시간에서는 개선이 보이지 않는다.**

### 문서에 없던 비용: `dev` push가 67초 → 163초

`cache-to`를 붙인 쪽의 대가를 계산에 넣지 않았다. `image-publish`의 빌드 스텝이 이전 세 번(67·75·67초)에서 **163초**로 늘었고, 로그가 원인을 정확히 지목한다.

```text
#20 exporting to GitHub Actions Cache ... DONE 91.4s
```

`mode=max`가 모든 중간 레이어를 내보내는 비용이다. 멀티스테이지 빌드에서 우리가 캐시하려는 `dependencies` 레이어는 **버려지는 build 스테이지 안에 있어** `mode=min`으로는 잡히지 않는다. 즉 이 비용은 이 캐시 방식의 필수 조건이다.

| | 변화 |
|---|---|
| PR 실행 1회 | **−13초** |
| `dev` push 1회(머지당) | **+96초** |

**총 CI 시간으로는 순손실이다** — PR당 실행이 7~8회는 돼야 손익분기이고 실제로는 2~4회다. 그럼에도 **유지하기로 정했다**(담당자 판단, 2026-08-01): 사람이 기다리는 것은 PR 피드백이고 `dev` push는 배포를 96초 늦출 뿐이라, 그 교환이 값어치가 있다고 봤다.

**아직 모르는 것**: 163초는 캐시를 **처음 채운** 실행이다. 다음 머지부터는 바뀐 레이어만 내보내 줄어들 수 있는데 데이터가 1건이라 단정할 수 없다. 다음 `dev` 머지에서 이 값을 다시 재고 여기 덧붙인다.

### 실행을 반복해도 이득은 커지지 않는다

`dependencies` 레이어가 이미 `CACHED`라 더 캐시될 여지가 없고, **48초가 이 `Dockerfile`의 바닥이다.** PR 실행은 설계상 캐시를 읽기만 하므로(위 「캐시를 PR에서는 읽기만 하는 이유」) 같은 브랜치에서 몇 번을 돌려도 캐시 상태가 그대로다 — 축적되는 것이 없다.

반대 방향 위험은 있다. GHA 캐시는 7일간 쓰이지 않으면 삭제되고 한도가 저장소당 10GB다. 다른 브랜치가 한도를 채우면 이 항목이 밀려나 61초로 돌아간다.

### 문서 전용 건너뛰기는 실제로 돈다 — 3.5분 → 14초

이 정정 문서 자신이 첫 실사례가 됐다(run `30643111065`). 추정했던 약 30초보다도 빨랐다.

| 스텝 | 결과 |
|---|---|
| 체크아웃 · `Detect documentation-only changes` · 마이그레이션 불변성 · wrapper 검증 | 실행 |
| `setup-java` · `setup-gradle` · `Run checks` · 리포트 업로드 · Buildx · 이미지 검증 | **건너뜀** |
| **`backend-ci / check`** | **success — 잡 전체 14초** |

의도한 것이 그대로 나왔다: 필수 상태 검사가 **보고**되어 `MERGEABLE`·`CLEAN`이고, 건너뛴 것은 잡 안의 스텝뿐이다([BD-42](../decisions/BD-42-ci-skip-inside-job-not-paths-ignore.md)가 `paths-ignore`를 버린 이유가 바로 이 보고를 잃지 않기 위함이었다).

남는 14초는 러너 부팅과 체크아웃이며 0으로 내릴 수 없다고 적어 둔 그 값이다. **문서 PR에 관해서는 이 티켓이 약속한 것이 실측으로 확인됐다.**

### 그래서 남은 지렛대는 캐시가 아니다

1. **이중 컴파일 제거 (−33초)** — 위 「남겨 둔 것」 그대로다. 캐시로 못 지우는 유일한 구간이고 캐시 왕복 비용도 없다.
2. **Gradle configuration cache · build cache** — 123초짜리 `Run checks`가 본체다. 빌드 로그가 직접 권한다: *"Consider enabling configuration cache"*.
