# BI-33. 로컬 스택 API 종단 검증 하네스

- **상태**: ✅ 완료
- **날짜**: 2026-07-31
- **관련**: Jira 작업,
  [BD-03](../decisions/BD-03-api-response-envelope.md)·[BD-04](../decisions/BD-04-cursor-pagination.md)(검사 대상 계약),
  [BD-07](../decisions/BD-07-context-immutability.md)·[BD-08](../decisions/BD-08-soft-delete-no-restore.md)·[BD-11](../decisions/BD-11-minimum-holding-invariants.md)·[BD-12](../decisions/BD-12-duplicate-record-idempotent.md)·[BD-20](../decisions/BD-20-selective-denormalization.md)·[BD-25](../decisions/BD-25-context-origin-created-at.md)·[BD-33](../decisions/BD-33-published-at-database-invariant.md)·[BD-37](../decisions/BD-37-ai-derived-invalidation-inside-deletion-transaction.md)(검사하는 불변식),
  [BI-12](BI-12-2026-07-28-deletion-cascade.md)(삭제 파급)·[BI-29](BI-29-2026-07-30-member-withdrawal.md)(탈퇴 파급)

## 왜 필요했나

세 층의 검증이 있었지만 어느 것도 "실제 스택에서 29개 API가 다 돌고 DB에 제대로 반영되는가"에
답하지 못했다. Testcontainers 통합 테스트는 격리된 DB의 소량 데이터를 보고, `seed/smoke.sh`은
읽기 9종만 훑고, Swagger UI는 응답에 실리지 않는 DB 상태를 못 보여준다.
`collection.record_count`(BD-20) 같은 비정규화 컬럼은 어긋나도 200이 온다.

## 산출

`loadtest/`에 세 조각. k6는 HTTP만 알고 SQL 검증기는 DB만 알며, `tools/run.sh`이 둘을 엮는
유일한 자리다 — k6는 SQL도 RSA 서명도 못 하므로 이 분리는 선택이 아니다.

| 조각 | 내용 |
| --- | --- |
| `k6/functional.js` + `k6/lib/` | 1 VU 전수 시나리오. 엔드포인트 29개, 검사 138개(상태 코드·엔벨로프·커서 계약·쿠키 속성), 만진 행 id를 `##TOUCHED##` 표식으로 출력 |
| `sql/verify-by-id.sql` | 표식의 id를 지목 검증 — BD-20·BD-08·BD-25·BD-11 파급·BD-33 |
| `sql/verify-invariants.sql` | DB 전역 불변식 17종 스윕. `owner` 열로 back/ai 소유를 가른다 |
| `tools/run.sh` | 전제 확인 → 전용 회원 생성 → 토큰 발급 → k6 → 지목 SQL → 전역 SQL → 지연 리포트 → 정리(trap) |
| `tools/mint-tokens.sh` | openssl로 RS256 토큰 발급(k6에 RSA sign이 없다) |
| `tools/{setup,teardown}-test-member.sql` | 쓰기 전용 회원의 생성·하드 삭제. 골든 id(≤6) 보호 가드 내장 |

실행은 `bash loadtest/tools/run.sh` 한 번이다. 전제·종료 코드·덮지 않는 범위는
[loadtest/README.md](../../../loadtest/README.md)에 있다.

## 검증 결과 (2026-07-31, member 3,007 · record 117k · context 155k)

**138/138 검사 통과, 29개 전수 호출, 종료 코드 0.** 검증 중 확인된 계약: BD-12 멱등
저장(같은 place 재요청 → 200 `CONTEXT_ADDED`, 같은 recordId), BD-07 교체 생성(새 contextId),
BD-11 삭제 확인 409 + `error.impact` 두 경로, 탈퇴 파급 전량(아래), CSRF·401·404 은닉·422
자기 팔로우, 검색의 두 분기(FastAPI 기동 시 200·미기동 시 503 `SEARCH_UNAVAILABLE`).

부류별 지연(1 VU 기준선, 절대 성능이 아니라 회귀 비교용):

| 부류 | p50 | p95 | p99 |
| --- | --- | --- | --- |
| detail | 5.1ms | 12.2ms | 12.3ms |
| list | 5.3ms | 6.5ms | 6.5ms |
| map | 7.0ms | 16.0ms | 17.2ms |
| feed | 32.3ms | — | — |
| search | 7.1ms | 415.5ms | 451.8ms |
| write | 7.5ms | 17.9ms | 62.5ms |

## 전역 불변식이 찾은 것과 삼분류

`back` 소유 위반 **4,696건**이 나왔고, 전수 삼분류 결과 **전부 시드 데이터가 원인**이다.
코드 결함은 0건이다.

| 검사 | 건수 | 판정 근거 |
| --- | --- | --- |
| BI-12 삭제된 Collection의 살아있는 연결 | 2,390 | 위반 행 100%가 대량 더미 대역(collection id 45~12488, member>6). 골든 0건 |
| BI-12 탈퇴 회원의 살아있는 Record | 2,169 | 탈퇴 회원 151명 전원이 대량 더미. 시드 생성기가 피드의 "작성자 미탈퇴 판정"을 시험하려고 **일부러** 만든 상태(seed README에 명시) |
| BD-11 활성 Collection의 연결 0건 | 137 | 100% 대량 더미. 생성기가 DB 직접 INSERT라 `@NotEmpty` 검증을 지나지 않는다 |

**API로는 재현되지 않는다**는 증거 둘:

1. 하네스 자신의 쓰기(생성·교체·409→force 연쇄·탈퇴)가 존재하는 상태로 전역 스윕이 돌았는데
   (스윕이 teardown보다 먼저다) `back_violations`는 매 실행 정확히 4,696으로 불변이었다.
2. 탈퇴 시나리오가 직접 실증한다 — 전용 회원이 record·context·collection·연결·follow를 만든 뒤
   `DELETE /v1/me`를 호출하고, 스윕이 그 회원을 어떤 검사에서도 잡지 않았다.
   `MemberWithdrawalService`의 파급(BI-29)이 전량 동작한다는 뜻이다.

시드 원인 불정합의 수정은 이 PR 범위 밖이다(`seed/`는 개인 작업 폴더 자산). 다음 시드
재생성 때 생성기가 파급 규칙을 지키도록 고치면 사라진다.

`ai` 소유는 고아 `ai.context_ai_state` 행뿐이다(실행마다 누적 — teardown이 `core`만 지우는
의도된 잔여, README「알려진 잔여물」에 명시). BD-37의 실질 검사 셋(임베딩·키워드 잔존,
CANCELLED 아님)은 **0건**이다.

## 만들면서 하네스가 잡은 것

완성 전에 하네스 자신이 걸러낸 실전 결함들이다. 도구가 일하기 시작한 증거로 남긴다.

- **엔드포인트 열거 누락** — 최초 열거가 27개였는데 `DELETE /v1/me`(#120)가 빠져 있었다.
  열거 시점의 체크아웃이 원격보다 뒤처져 있었던 탓이다. 전수 판정 로직(`missedEndpoints`)이
  아니라 사람의 사전 열거가 틀렸다는 점이 요지다 — 전수 기준 목록도 검증 대상이다.
- **CSRF 토큰 캐시 불가** — 서버가 매 응답 XSRF-TOKEN 쿠키를 회전시키므로(`CsrfCookieFilter`)
  세션당 캐시하면 두 번째 요청부터 403이다. 항아리에서 매번 새로 읽어야 한다.
- **낡은 골든 참조** — 시드 문서의 `SEED-0008` 형식 kakao id가 현 DB(카카오 실 장소, 숫자 id)에
  없었다. 문서와 데이터의 어긋남을 스모크가 아니라 계약 검사가 잡았다.
- **Windows 함정 셋** — 셸 인라인 한글은 CP949로 깨져 400이 된다(UTF-8 파일로만 전달),
  `C:\WINDOWS\system32\bash.exe`는 WSL이라 openssl이 없다(Git Bash 명시), 네이티브 python은
  MSYS `/c/` 경로를 못 받는다(`sys.argv` 전달).
- **합성 회원의 불가능 상태** — SQL로 회원 행만 만들면 `GET /v1/me/summary`가 500을 낸다.
  가입이 회원과 소셜 계정을 한 트랜잭션에 만들므로 "소셜 계정 없는 활성 회원"은 API로 도달
  불가능한 상태이고, `MemberSummaryService`는 그 상태를 **계약대로** `IllegalStateException`으로
  던진다(javadoc에 명시된 동작 — 조용히 빈 값을 내면 "email은 항상 있다" 계약이 깨진 채
  프론트로 나간다). setup이 소셜 계정까지 만들어 실제 가입 결과와 같은 모양을 갖추는 것으로
  풀었고, 덕분에 탈퇴 시나리오가 소셜 계정 마스킹 경로까지 실제로 지나가게 됐다.

## 관측 (수정하지 않고 기록만)

- **지도 응답 61KB** — `GET /v1/records/map`은 페이지네이션이 없어 상위 사용자(마커 697개)의
  응답이 61,184바이트다. 1 VU에서는 문제가 아니며, 부하 단계(2단계)에서 대역폭·직렬화 비용으로
  드러나는지 보고 판단한다.
- **검색 p95 415ms** — FastAPI 임베딩 왕복 포함 값. 임계값 1,500ms 안쪽이다.

## 덮지 않는 범위

소셜 로그인 콜백(OAuth 왕복 필요), `refresh` 정상 경로, `feed/events`의 DB 단정(AI 소유
V102), 검색 품질(시드 임베딩이 합성값), 절대 성능 수치. 근거는 README에 있다.
