# API 종단 검증 하네스

로컬 스택과 대량 데이터로 28개 API를 전수 호출해 **응답 계약과 DB 상태를 함께** 검증한다.
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
6. **부하 프로파일** — 2단계 범위다. 이 하네스는 1 VU 1 iteration이다

## 알려진 잔여물

`teardown-test-member.sql`은 `core`만 지운다. `ai.context_ai_state` 등은 `core.context`를
FK 없이 `context_id`로 참조하므로 **고아 AI 행이 남는다.** `verify-invariants.sql`이
`owner='ai'`로 보고하되 `back_violations`에 세지 않는다 — `ai` 스키마는 SELECT만 하는 영역이다.
