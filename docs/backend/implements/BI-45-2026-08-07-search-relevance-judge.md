# BI-45. 검색 결과 LLM 관련도 재판정(4번째 신호) 구현

- **상태**: ✅ 구현 완료. 기능 플래그는 꺼진 상태로 두었다. 켜는 결정은 별도다.
- **날짜**: 2026-08-07
- **추적**: Jira 작업([AI] 검색 결과 신뢰도 개선). 사용자가 실배포에서 발견한 검색 순위 오류를 직접 지시해 시작한 작업이다.
- **관련**: ai 레포 `Jira-relevance-judge` 브랜치(`app/client/relevance_client.py` 등, `POST /internal/v1/search/judge` 신설) · `docs/backend/implements/BI-43-2026-08-06-search-lexical-merge.md`(같은 서비스의 앞선 신호)

## 배경

배포 직후 사용자가 검색 결과 오류를 보고했다. 질의 "예전에 부트캠프 때 다녔던 헬스장 어디였지?"에서, 본문에 "부트캠프"가 그대로 있는 기록이 2위로 밀리고 그 단어가 없는 기록이 1위에 올랐다.

원인은 기존 세 신호(재작성·문자열 검색·키워드 재정렬) 모두의 사각지대다. 이 질의는 문장형이라 재작성(6자 이하만 대상)과 문자열 검색(단어형만 대상)이 적용되지 않고, "부트캠프"는 키워드 목록에 없는 고유명사라 재정렬도 잡지 못한다. 남는 것은 순수 벡터 유사도뿐이고, 임베딩은 "본문에 그 단어가 정확히 있는가"보다 전체적인 의미 유사도를 본다.

사용자가 4번째 신호를 직접 제안했다. 기존 파이프라인의 최종 후보를 LLM에게 질의와 함께 보여주고 관련도 4단계(`VERY_RELEVANT`~`NOT_RELEVANT`)로 재판정해, 무관한 것은 제거하고 나머지를 재정렬한다. RAG 분야의 "LLM reranker" 패턴이다. 검색 신호 3종 동결(ai 레포 P49) 위에 얹는 4번째 신호이므로 동결의 재론이 아니라 소유자(사용자)의 확장 결정으로 처리했다.

## 아키텍처 결정

ai(FastAPI)는 Context 본문을 저장하지 않는다. 본문의 유일한 소유자는 back/Core DB다. 그래서 back이 3신호 병합까지 끝난 최종 후보(본문 포함)를 ai에 보내 판정만 받아오는 구조로 갔다. "FastAPI는 본문을 반환하지 않는다"는 공용 계약(05_AI_설계)은 ai→back 응답 방향의 조항이라 이 방향(back→ai 요청)을 막지 않는다 — `ContextProcessRequest.text`가 이미 같은 방향의 선례다.

back이 직접 LLM을 호출하는 대안은 검토 후 배제했다. back에는 LLM 호출 인프라가 전무해 벤더 체인·구조화 출력 파싱·재시도 정책을 전부 새로 만들어야 하지만, ai에는 이미 그 인프라(`LLMClient`, 벤더 체인, 구조화 출력 파싱)가 있다. back 쪽은 `AiSearchClient`를 본뜬 클라이언트 하나만 추가하면 된다.

## 산출

- **ai 레포**: `POST /internal/v1/search/judge` 신설. 요청 `{query, candidates: [{contextId, placeName, body}]}` → 응답 `{results: [{contextId, relevance}]}`. 기본 꺼짐(`SEARCH_RELEVANCE_JUDGE_ENABLED`). 상세는 그 레포 커밋(`Jira-relevance-judge` 브랜치).
- **`AiRelevanceJudgeClient`** 신설(`domain/ai/client`). `AiSearchClient`를 본떴지만 실패 정책은 반대다 — 이 클라이언트는 보조 신호라 실패를 흡수하지 않고 그대로 던진다. 흡수는 호출부의 책임이다.
- **`RelevanceJudgeProperties`** 신설. `pinlog.search.relevance-judge.enabled`, 기본값 꺼짐.
- **`AiProperties`**에 `judge` 타임아웃 필드 추가(`connect-timeout: 1s`, `read-timeout: 10s`). 후보 최대 10건의 본문을 한 번의 LLM 호출로 판정하는 동기 경로라 `search`(5s)보다 길게 잡았다.
- **`AiIntegrationConfig`**에 `aiJudgeRestClient` Bean 추가 — `search`·`process`와 타임아웃이 달라 전용 인스턴스가 필요하다.
- **`RecordSearchService`** 수정. `matchedContexts` 빌드 직후, keyword 조회 직전에 `judgeRelevance()`를 넣었다 — 이 지점이 3신호 병합까지 끝난 진짜 최종 후보가 모이는 유일한 지점이다. 흐름과 실패 정책은 `mergeLexicalMatches()`(BI-43)와 같은 강등 패턴이다: 플래그 꺼짐·판정할 후보 없음·판정 호출 실패는 모두 원본 순서를 그대로 돌려준다. `NOT_RELEVANT`는 제거하고 나머지는 (등급 desc, 원 순서)로 안정 정렬한다. 판정이 누락된 항목은 제거하지 않고 `RELEVANT`와 동급으로 취급한다. 모든 후보가 `NOT_RELEVANT`면 빈 결과를 그대로 신뢰한다.
- **테스트**. 플래그를 켠 컨텍스트의 `RelevanceJudgeSearchApiTests` 4건 — 사용자 보고 실사례와 같은 모양(벡터 유사도가 낮아도 관련도가 높으면 순위가 오르는 것), `NOT_RELEVANT` 제거, 전부 무관일 때 빈 결과, 판정 호출 실패 시 강등. 기본값 컨텍스트의 `RecordSearchApiTests`에 꺼짐 계약 1건 추가. `FastApiSearchStub`을 확장해 같은 대역이 `/internal/v1/search`와 `/internal/v1/search/judge`를 함께 받게 했다 — 두 클라이언트가 같은 `pinlog.ai.base-url`을 보므로 대역도 하나여야 한다.

## 검증

- `./gradlew clean check --no-daemon` 통과 (checkstyle 경고 2건은 테스트 메서드명의 "a/an" 관사 접두사 규칙 위반이라 수정 후 재통과).
- 사용자가 보고한 정확한 사례(피치플레이헬스 vs MH토탈휘트니스)는 시딩 데이터에 없어(사용자 개인 배포 데이터) 그 정확한 케이스로 로컬 재현은 불가능했다. 같은 실패 모양(본문에 질의어가 명시된 후보 vs 없는 후보)을 재현하는 테스트 픽스처로 대체 검증했다.
- 시연 DB·스냅샷 DB 반영, 플래그 활성화는 이번 범위 밖이다. 켜는 결정은 별도로 한다.
