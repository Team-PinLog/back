# BI-44. 결합 신뢰도 게이트 구현

- **상태**: ✅ 구현 완료. 기능 플래그는 꺼진 상태로 두었다. 켜는 결정은 검색 고도화 검증 게이트 통과 뒤의 일이다.
- **날짜**: 2026-08-07
- **추적**: Jira 작업
- **관련**: `OFFTOPIC-CONFIDENCE-GATE-HANDOFF-DRAFT.md`(중앙 조정 세션 인계 문서) §4 · ai 레포 `docs/proposals/P49-multi-signal-search.md` · ai 레포 `docs/implements/2026-08-07-gate-threshold-remeasure.md`(임계값 오프라인 재측정, Jira 작업) · [BD-52](../decisions/BD-52-confidence-gate-filters-before-core-revalidation.md)(게이트 위치 결정)

## 배경

현행 검색은 유사도 하한(τ_abs·r)으로 관련 없는 결과를 자르지만, 무관한 질의의 최고 유사도가 실제 정답의 유사도보다 높게 나오는 역전이 실측으로 확인됐다(중앙 조정 세션 §2.2). 임계값 하나로는 잡음을 다 자르면서 정답을 다 살릴 수 없다.

인계 문서 §4가 제안한 게이트는 벡터 유사도 하나만으로 판정하지 않는다. 컷을 통과한 결과마다 근거 신호 세 가지 — S1(벡터 컷 통과)·S2(문자열 매치)·S3(키워드 매치) — 를 세고, S1 하나뿐이고 유사도가 낮으면 그 결과를 응답에서 뺀다. S2나 S3가 하나라도 있으면 유사도가 낮아도 남긴다 — 문자열이나 키워드로 뒷받침되는 결과는 벡터 유사도의 역전 문제에서 자유롭기 때문이다.

## 산출

- **`ConfidenceGateProperties`** 신설. `pinlog.search.gate.enabled`는 게이트를 켜고 끄는 설정이고 기본값은 꺼짐이다. `similarity-threshold`는 S1 단독 결과를 제외하는 유사도 하한이고 기본값은 0.35 — ai 레포가 이 용도로 별도 오프라인 재측정한 채택값이다(Jira 작업).
- **`AiSearchResponse.Match`에 `keywordMatched` 필드 추가.** ai 레포가 검색 응답 스키마에 이미 실어 보내는 값이다(ai 레포 Jira 작업). 재정렬이 이미 계산하지만 순서에만 쓰고 버리던 신호를 back까지 살렸다.
- **`RecordSearchService` 수정.**
  - `mergeLexicalMatches`가 병합된 목록뿐 아니라 문자열로 매치된 Record id 집합(S2 신호)도 함께 돌려주도록 반환 타입을 `LexicalMergeResult`로 바꿨다. 그 신호는 `rrfMerge` 안에서만 알고 버려지던 것이었다.
  - `applyConfidenceGate`를 신설해 문자열 병합 직후·Core 재검증 이전에 게이트를 건다(위치 근거는 BD-52). S2·S3 중 하나라도 있으면 유사도와 무관하게 통과시키고, 아니면 유사도가 `similarityThreshold` 미만인 것만 뺀다.
  - 문자열 단독 항목(`similarity == 0.0`)은 그 자체로 S2 신호가 있는 Record이므로 이 규칙에서 별도 분기 없이 항상 살아남는다.
- **테스트**. 플래그를 켠 컨텍스트의 `ConfidenceGateApiTests` 6건과, 기본값 컨텍스트의 `RecordSearchApiTests`에 추가한 꺼짐 계약 1건이다. 6건이 고정하는 계약은 넷이다. 유사도가 낮고 다른 신호도 없는 결과가 빠지는 것, 유사도가 임계값과 같으면(`>=`) 남는 것, 유사도가 낮아도 키워드 매치가 있으면 남는 것, 문자열 단독 항목은 게이트 판정 대상이 아니라는 것이다.

## 설계 판단

### 게이트 위치 — 응답 조립 이전

인계 문서는 "응답을 최종 조립하는 단계"에 게이트를 두라고 적었다. 실제로는 문자열 병합 직후·Core 재검증 이전에 걸었다 — 세 신호가 전부 갖춰지는 가장 이른 지점이고, 사용자가 보는 최종 응답은 어느 지점에서 걸러도 같다. 게이트가 뺄 Record까지 Core에 소유권·삭제 여부를 묻는 조회를 만들지 않는 이점도 있다. 자세한 선택지 비교는 BD-52에 있다.

### 임계값 0.35의 출처 — 재사용이 아니라 재측정

인계 문서는 threshold 후보로 0.35를 들면서 그 값이 `SEARCH_KEYWORD_RERANK_FLOOR`(질의-Preset 코사인이 같은 의미인지 판단하는, 전혀 다른 용도의 값)에서 가져온 것이라 "이 재사용이 실제로 타당한지는 아직 검증되지 않았다"고 스스로 명시했다. ai 파트가 이 프로젝트의 기존 관행(같은 숫자라도 구조가 바뀌면 재측정한다, ai 레포 `app/core/config.py`의 `SEARCH_KEYWORD_RERANK_FLOOR` 주석 참고)을 따라 이 용도로 별도 오프라인 재측정을 했고(Jira 작업), 그 결과 0.35가 정답 손실 없이 무관 노출을 크게 줄이는 값임을 확인했다. back은 그 채택값을 설정 기본값으로만 가져왔다 — 값을 back이 직접 정하지 않았다.

### `keywordMatched`의 null 방어

ai 스키마는 이 필드를 항상 보내지만(필수, 기본값 없음), `AiSearchResponse.Match`는 다른 필드(`recordId`·`contextId`·`similarity`)와 마찬가지로 상대 응답을 신뢰하지 않는다는 이 클래스의 기존 원칙을 따른다. 다만 급이 다르다 — `recordId`가 없으면 그 Match 전체를 버리지만(`distinctByRecord`), `keywordMatched`가 없거나 `null`이면 그 Match를 버리지 않고 신호 없음(false)으로만 본다. S3는 판정을 보강하는 신호이지 Record를 식별하는 값이 아니기 때문이다.

## 남은 것

- 이 구현은 오프라인 재측정(Jira 작업)이 재구성한 S2·S3 신호를 실서버에서 그대로 재현한다는 전제 위에 있다. `lexical_sweep.py`류의 실서버 on/off 대조가 아직 이 게이트를 대상으로 돈 적은 없다 — 검증 게이트 통과 전에 필요하다.
- `.claude/handoff/SEARCH-UPGRADE-HANDOFF.md`(인계 문서가 가리킨 위치)는 `ai`·`back`·`docs` 어디에도 없었다. 이 구현은 그 문서 대신 P49 제안서와 `search-upgrade` 브랜치의 실제 코드를 근거로 삼았다(BD-52에도 같은 내용을 남겼다).
