# API 종단 검증 하네스

로컬 스택과 대량 데이터로 29개 API를 전수 호출해 **응답 계약과 DB 상태를 함께** 검증한다.
Testcontainers 통합 테스트를 대체하지 않고 그 위에 얹는다 — 이쪽은 격리된 DB가 아니라
실제로 뜬 스택을 본다.

```bash
bash tools/run.sh
```

## 왜 필요한가

Swagger UI로는 응답에 실리지 않는 DB 상태를 볼 수 없다. `POST /v1/collections/{id}/records`가
200을 줘도 `collection.record_count`(BD-20의 비정규화 컬럼)가 실제 연결 수와 어긋날 수 있고,
그 어긋남은 응답 본문에 나타나지 않는다.

## 구조

| 조각 | 아는 것 | 모르는 것 |
| --- | --- | --- |
| `k6/functional.js` | HTTP 계약, 응답 지연 | DB |
| `sql/verify-by-id.sql` | 이번에 만진 행 | HTTP |
| `sql/verify-invariants.sql` | DB 전역 | HTTP |
| `tools/run.sh` | 순서와 정리 | — |

k6는 SQL을 못 치므로 이 분리는 선택이 아니다. `xk6-sql`로 k6 안에서 SQL을 칠 수는 있으나
커스텀 바이너리 빌드가 필요해 팀 재현성이 나빠진다.

k6는 RSA 서명도 못 하므로(`k6/crypto`에 RSA sign이 없다) 토큰은 `tools/mint-tokens.sh`이
openssl로 미리 만들어 `artifacts/tokens.json`으로 넘긴다.

`k6`는 실행 중 파일을 쓸 수 없고 `handleSummary`는 VU 런타임 상태를 보지 못한다. 그래서
`recorder.js`가 표준출력에 `##TOUCHED##` 표식 한 줄을 흘리고 `run.sh`이 그것을 잘라낸다.

## 전제

- 로컬 스택 4개가 떠 있어야 한다: 앱 8080 · FastAPI 8000 · Postgres 15432 · Redis 16379
- `back/.env`에 `JWT_PRIVATE_KEY`가 있고 **앱이 그 키로 떠 있어야** 한다. 없으면 앱이 부팅마다
  임시 키를 만들어 발급한 토큰이 전부 401이 된다
- k6 v2.1.0. 기본 경로는 `C:\Program Files\k6\k6.exe`이며 `K6_BIN`으로 덮을 수 있다
- **Git Bash로 실행한다.** `C:\WINDOWS\system32\bash.exe`는 WSL이라 openssl이 없어 토큰
  발급이 exit 127로 죽는다
- psql은 PATH에 없어도 된다. `docker exec`로 컨테이너 안의 psql을 쓴다

## 데이터를 어떻게 다루는가

- 쓰기는 **전용 테스트 회원**으로만 한다. 회원 생성 API가 없어(소셜 로그인뿐) SQL로 만들고
  끝에 그 회원의 자원만 하드 삭제한다
- **골든 id는 읽기로만 만진다**: member 1~6 · place 1~25 · record 1~25 · collection 1~7.
  `run.sh`이 실행 전후 골든 행 수를 비교한다
- `core.place`는 여러 회원이 공유하므로 정리 대상이 아니다

## 종료 코드

| 코드 | 뜻 |
| --- | --- |
| 0 | HTTP 계약 검사와 지목 검증 통과 |
| 1 | 계약 검사 또는 지목 검증 실패 |
| 2 | 전제 미충족(스택 미기동, k6 없음 등) |

**전역 불변식 위반은 종료 코드에 반영하지 않는다.** 기존 데이터에 이미 있던 불정합일 수 있고,
그중 `ai` 소유 항목은 이쪽에서 고칠 수 없다. 리포트의 `소유별 합계`에서 `back_violations`를 본다.

## 덮지 않는 범위

이 하네스는 아래를 검증하지 않는다. 덮은 척하지 않기 위해 적어 둔다.

1. **소셜 로그인 콜백** — 실제 OAuth 왕복이 필요하다. `GET /v1/auth/{provider}/login`의 302와
   `Location`까지만 본다. 콜백은 `KakaoNaverLoginCallbackTests`·`GoogleLoginCallbackTests`가 덮는다
2. **`POST /v1/auth/refresh`의 정상 경로** — refresh 쿠키를 로그인 없이 얻을 수 없어 401 계약만
   본다. 재사용 감지와 패밀리 무효화(BD-35)는 `AuthTokenContractTests`가 덮는다
3. **`POST /v1/feed/events`의 DB 단정** — `core.feed_event`는 AI 파트(이정헌) 소유(V102)다.
   상태 코드 계약만 확인한다
4. **검색 품질** — 대량 컨텍스트는 템플릿 조합이고 임베딩은 프리셋 주변에 뿌린 값이다.
   응답 형태와 지연만 본다
5. **절대 성능 수치** — 로컬 Windows 단일 인스턴스다. 회귀 비교와 구조적 병목 탐지에만 쓴다
6. **자연어 검색 부하** — 질의마다 OpenAI 임베딩 실 호출이 나가는 AI 파트 소유 자원이라
   부하 범위에서 뺐다. 검색 부하는 담당자 합의 후 별도 티켓으로 진행한다

## 부하 프로파일 (Jira 작업)

기능 전수 검증과 별개로, 프로파일 4종 × 계열을 돌려 병목을 관측한다.

```bash
bash tools/run-load.sh <read|write|mixed> <smoke|average|stress|spike>
bash tools/run-load.sh all     # 유효 조합 10개 전부 (약 70~90분)
```

| 프로파일 | 모양 | 용도 |
| --- | --- | --- |
| smoke | 1 VU × 1분 | 시나리오 회귀 확인 |
| average | 20 VU 지속 5분 | 평상 부하 기준 |
| stress | 20→100 VU 계단 8분 | 포화 지점 탐색 |
| spike | 5→150→5 VU 4분 | 급증 회복력 |

- **읽기**(`load-read.js`): VU를 지도·목록·상세·피드에 고정, kind 태그로 부류별 지연 분리.
  지도 VU 절반은 heavy 회원(마커 697개)이다.
- **쓰기**(`load-write.js`): 처리량(VU마다 전용 회원 — 사전 SQL 생성)과 경합(VU 10 고정이
  한 공유 Record의 Context를 몰아침 — BD-11 직렬화 측정)을 태그로 가른다. 삭제는 Collection
  먼저, Record force 나중 — 반대 순서는 연쇄 탓에 가짜 404가 오류율을 부풀린다.
- **혼합**(`load-mixed.js`): 가중 여정(피드40·지도20·상세25·쓰기15 — 출시 전 추정치).
  average·stress 전용.
- 실행마다 전역 불변식 스윕을 전후로 돌린다 — back 위반 수가 움직이면 그 실행은 실패다.
- 산출물은 `artifacts/load/<계열>-<프로파일>/`(summary.json·metrics.csv·k6.log·invariants-*),
  표 생성은 `PYTHONUTF8=1 python tools/collate-load.py artifacts/load`.
- 결과와 병목 판정은 `docs/backend/implements/BI-37`에 있다.

부하 실행은 `ai.context_ai_state` 고아를 **대량으로** 남긴다(쓰기 여정의 context churn).
알려진 잔여물 절 참고 — 정리 방식은 AI 파트와 공동 결정 사안이다.

## 대량 벤치 (Jira 작업)

인덱스·쿼리 계획 검증용 볼륨을 만든다. 현재 시드(record 117k)에서는 플래너가 Seq Scan을
골라도 손해가 없어 인덱스 설계의 옳고 그름이 판별되지 않는다 — record 1천만 · 회원 10만
규모에서만 계획이 갈린다.

```bash
bash tools/run-massive.sh                # 회원 10만 → record 1천만 (적재+검증)
MEMBERS=1000 bash tools/run-massive.sh   # 축소 실행(약 11만 건) — 생성기 자체 확인용
```

- **이어 붙인다, 지우지 않는다.** 새 id를 현재 최대값 뒤에 만들어 골든 셋(member 1~6 ·
  place 1~25 · record 1~25)과 기존 시드를 보존한다. 되돌리기는 `tools/teardown-massive.sql`
  (벤치 행만 표식 `BENCH-`로 찾아 걷어냄) — 통째 초기화면 볼륨 삭제가 훨씬 빠르다.
- **분포는 구간표다** (균등이면 선택도 추정이 현실과 어긋난다): monster 0.05%×20,000건 ·
  heavy 0.95%×4,000 · mid 9%×400 · light 40%×30 · tiny 50%×8 → p50 두 자리 · p90 세 자리 ·
  max 다섯 자리.
- **`ai` 스키마와 `core.feed_event`는 만들지 않는다**(AI 파트 소유). 따라서 이 볼륨으로는
  Keyword 집계·노출 패널티 쿼리를 검증할 수 없다.
- 검증(`tools/verify-massive.sql`)은 행 수·분포·골든 보존·통계 갱신에 더해 값 정합
  (BD-11·20·33)을 PASS/FAIL로 판정하고, FAIL이 있으면 0이 아닌 코드로 끝난다.

### 측정 전 자원 한도

로컬 Docker는 메모리가 15GiB라 천만 행도 페이지 캐시에 다 들어간다(적중률 99.99%) —
그대로 재면 "전부 메모리에 있는" 비현실을 잰다. 측정 전에 운영과 같은 한도(1Gi·1cpu,
`infra/platform/postgres/statefulset.yaml`)를 얹는다:

```bash
docker compose -f compose.yaml -f compose.bench.yaml up -d postgres   # 한도 얹기
docker compose up -d postgres                                         # 되돌리기
```

**적재는 한도 없이, 측정은 한도 얹고.** 순서가 바뀌면 인덱스 재생성(maintenance_work_mem
256MB)이 1Gi 안에서 기어간다 — run-massive.sh가 한도 걸린 컨테이너를 감지하면 중단한다.

적재 실측(2026-08-03, 회원 10만 → record 1,000만): 적재+인덱스 재생성+ANALYZE **296초**,
DB 707MB → **5,906MB**. 검증 10종 전부 PASS — 회원당 record 분포 p50 19 · p90 103 ·
max 20,000(실측 형태 유지), 1Gi 한도 상태 콜드 스캔에서 캐시 적중률 0%·디스크 읽기
369,185블록(약 2.9GB)으로 한도가 실제 I/O를 만들어 내는 것을 확인.

## 알려진 잔여물

`teardown-test-member.sql`은 `core`만 지운다. `ai.context_ai_state` 등은 `core.context`를
FK 없이 `context_id`로 참조하므로 **고아 AI 행이 남는다.** `verify-invariants.sql`이
`owner='ai'`로 보고하되 `back_violations`에 세지 않는다 — `ai` 스키마는 SELECT만 하는 영역이다.
