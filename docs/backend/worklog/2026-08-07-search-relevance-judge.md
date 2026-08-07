# 검색 4번째 신호(LLM 관련도 재판정)를 추가했다

- **날짜**: 2026-08-07
- **관련**: [BI-44](../implements/BI-44-2026-08-07-search-relevance-judge.md) · ai 레포 `S15P11A705-relevance-judge` 브랜치 · [BI-43](../implements/BI-43-2026-08-06-search-lexical-merge.md)(앞선 세 신호)

사용자가 실배포에서 발견한 검색 순위 오류(문장형 질의에 포함된 고유명사가 세 신호 모두의 사각지대에 걸려 관련 기록이 무관한 기록보다 낮은 순위로 나온 사례)를 교정하기 위해 4번째 검색 신호를 추가했다. ai가 후보의 LLM 관련도를 4단계로 재판정하고, back은 그 결과로 무관한 결과를 걸러내고 재정렬한다.

구조는 back이 3신호 병합까지 끝난 최종 후보(본문 포함)를 ai의 신규 엔드포인트(`POST /internal/v1/search/judge`)에 보내는 방식이다. ai는 Context 본문을 저장하지 않으므로 back이 능동적으로 본문을 실어 보낸다 — "FastAPI는 본문을 반환하지 않는다"는 계약은 응답 방향의 조항이라 이 요청 방향을 막지 않는다.

`AiRelevanceJudgeClient`를 `AiSearchClient`를 본떠 신설했지만 실패 정책은 반대로 했다 — 이 신호는 보조 신호라 클라이언트는 실패를 삼키지 않고 그대로 던지고, `RecordSearchService.judgeRelevance()`가 `mergeLexicalMatches()`(BI-43)와 같은 강등 패턴(플래그 → 게이트 → try/catch 흡수)으로 받는다. 실패해도 검색 자체는 성공하고 판정 이전 순서로 되돌아간다.

TDD로 진행했다: `RelevanceJudgeSearchApiTests` 4건(사용자 보고 실사례와 같은 모양의 순위 역전 교정, `NOT_RELEVANT` 제거, 전부 무관 시 빈 결과, 판정 실패 시 강등)을 켠 컨텍스트에, `RecordSearchApiTests`에 꺼짐 계약 1건을 기본값 컨텍스트에 추가했다. `FastApiSearchStub`을 확장해 `/internal/v1/search`와 `/internal/v1/search/judge`를 같은 대역·같은 포트에서 받게 했다 — 두 클라이언트가 같은 `pinlog.ai.base-url`을 보기 때문이다.

`AiProperties`에 `judge` 타임아웃 필드를 추가하면서 그 record를 직접 생성하는 기존 테스트 둘(`AiSearchClientTest`, `AiPlaceSuggestionClientStubTests`)이 컴파일 깨짐을 냈다 — 인자 하나를 추가해 고쳤다.

checkstyle이 테스트 메서드명 둘(`aVeryRelevantJudgmentOutranksAHigherSimilarityMatch`, `aFailedJudgeCallFallsBackToThePreJudgeOrder`)의 "a/an" 관사 접두사를 위반으로 잡았다 — 관사를 뺀 이름으로 고쳤다.

`./gradlew clean check --no-daemon`으로 전체 검증했다. ai 쪽 구현·검증(pytest 전량·ruff·문서 색인)은 그 레포 커밋 이력에 있다. 두 레포 모두 브랜치 커밋·push까지만 진행했고, dev 병합은 사용자 승인 후로 남겨 뒀다.
