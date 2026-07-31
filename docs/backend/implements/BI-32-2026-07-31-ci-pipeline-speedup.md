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

## 기대 효과

| 경우 | 전 | 후(기대) |
|---|---|---|
| 코드 PR | 약 3.5분 | 약 2.5분 (이미지 검증 61초 → 25~30초) |
| 문서 PR | 약 3.5분 | 약 30초 |

이미지 검증 쪽 수치는 캐시 적중을 전제한 기대값이다. **첫 PR은 아직 `dev`가 캐시를 채우지 않았으므로 종전과 같다** — 이 PR이 `dev`에 머지되면서 `image-publish`가 처음 항목을 쓰고, 그다음 PR부터 효과가 관측된다.

## 함께 고친 문서

`docs/development/code-style.md`가 *"`backend-ci / check`가 실행하는 `./gradlew clean check`"* 로 적고 있어 `clean` 제거와 어긋났다. CI와 로컬이 이제 다른 명령을 쓰므로 그 구분까지 적었다 — 로컬은 `build/`에 이전 결과가 남아 완료 보고 전 검증은 `clean check` 그대로다.
