# BD-52. 결합 신뢰도 게이트는 문자열 병합 직후·Core 재검증 이전에 건다

- **상태**: Accepted
- **날짜**: 2026-08-07
- **관련**: `OFFTOPIC-CONFIDENCE-GATE-HANDOFF-DRAFT.md`(중앙 조정 세션 인계 문서) §4 · ai 레포
  [P49](../../ai/proposals/P49-multi-signal-search.md) §4-5(similarity 비노출 계약) · 오프라인
  재측정 [S15P11A705-401](../../ai/implements/2026-08-07-gate-threshold-remeasure.md)
- **번호**: [BD-45](BD-45-worklog-per-entry-files.md)의 규칙대로 `dev` 머지 순서가 번호를 확정한다.
  머지 시점에 52가 이미 다른 결정에 쓰였다면 [BD-50](BD-50-datasource-redis-config-follows-infra-env-vars.md)의
  선례대로 번호만 옮기고 내용은 그대로 둔다.

## 맥락

`OFFTOPIC-CONFIDENCE-GATE-HANDOFF-DRAFT.md` §4는 검색 결과의 낮은 연관도 노출을 줄이기 위해
새 게이트를 제안했다 — S1(벡터)·S2(문자열)·S3(키워드) 세 신호 중 S1 하나뿐이고 유사도가 낮으면
결과에서 뺀다. 그 문서는 "응답을 최종 조립하는 단계"에 판단 함수를 두라고 적었지만, 실제
`RecordSearchService.search()`의 흐름(문자열 병합 → Core 재검증 → 조립 → bounds 계산)을 보면
"최종 조립"이 정확히 어느 지점을 가리키는지 자명하지 않다.

세 신호가 전부 갖춰지는 가장 이른 지점은 **문자열 병합 직후**다.

- S1(벡터 유사도)은 FastAPI 응답에 항상 있다.
- S2(문자열 매치)는 `mergeLexicalMatches`가 병합하는 순간 결정된다 — 그 이전엔 아직 문자열
  후보 목록조차 없다.
- S3(키워드 매치)는 ai 레포가 이미 응답 필드(`keywordMatched`, S15P11A705-399)로 실어 보낸다 —
  Spring이 계산하지 않는다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 문서 표현 그대로 "응답 조립(`assemble`) 이후"에 게이트를 건다 | 문서가 말한 "최종"이라는 표현에 더 가깝다 | `RecordSearchItemResponse`는 `keywordMatched`·문자열 매치 여부를 들고 있지 않다 — 그 정보를 조립까지 끌고 가려면 DTO를 늘리거나 별도 Map을 나란히 들고 다녀야 한다. 게다가 이미 뺄 것이 정해진 Record까지 Core 재검증(소유권·삭제 조회)을 거치는 낭비가 생긴다 |
| **(b) `mergeLexicalMatches` 직후·`searchRecordRepository.findVerified` 이전에 게이트를 건다** | 세 신호가 전부 갖춰지는 가장 이른 지점이라 추가 상태를 끌고 다닐 필요가 없다. 게이트가 뺄 Record는 애초에 Core 조회 대상에서 빠지므로 DB 조회가 준다 | "응답 조립"이라는 문서 표현과 정확히 같은 자리는 아니다 — 다만 사용자에게 보이는 최종 결과는 (a)와 완전히 같다 |

## 결정

**(b)를 골랐다.**

1. **사용자 관점에서 (a)·(b)는 같은 응답을 만든다.** 게이트는 "보여줄지 말지"만 정하고 그
   판단에 필요한 정보(유사도·S2·S3)는 이미 병합 단계에서 전부 확정돼 있다. 그 뒤 어느 단계에서
   걸러도 최종 응답은 같다 — 그래서 "최종 조립 단계"라는 문서의 표현은 결과적 위치이지 구현
   위치의 강제가 아니라고 읽었다.
2. **불필요한 DB 조회를 만들지 않는다.** 게이트가 뺄 Record까지 Core에 소유권·삭제 여부를
   묻는 것은 버릴 조회다. `mergeLexicalMatches` 직후에 걸면 그 조회 자체가 없다.
3. **새 필드를 DTO에 얹지 않는다.** `RecordSearchItemResponse`는 API 응답 계약이다.
   `keywordMatched`·문자열 매치 여부는 게이트 판단에만 쓰고 클라이언트에 노출하지 않는데(문서
   §4.1이 명시한 원칙 — "보여줄지 말지"는 완전히 새로운 마지막 단계이지 기존 필드의 의미를
   바꾸는 것이 아니다), (a)를 택하면 그 신호를 조립 단계까지 옮기기 위한 임시 구조가
   필요해진다. (b)는 `AiSearchResponse.Match`(이미 `keywordMatched`를 들고 있다)와 병합
   단계의 지역 변수(`lexicalMatchedRecordIds`)만으로 끝난다.

## 결과

- **이 결정으로 감수하는 것**
  - 게이트는 `List<AiSearchResponse.Match>` 단위로 판단한다 — `RecordSearchItemResponse`가
    만들어지기 전이다. 나중에 게이트 판단이 Core 데이터(예: Place 카테고리)를 필요로 하게
    되면 이 위치를 다시 바꿔야 한다.
  - `mergeLexicalMatches`의 반환 타입이 `List<Match>`에서 `LexicalMergeResult`(병합 목록 +
    문자열 매치 Record id 집합)로 바뀌었다. 이 메서드를 호출하는 곳이 늘면 그 신호도 함께
    옮겨야 한다는 것을 기억해야 한다.
- **재검토 트리거**
  - 게이트 판단이 벡터·문자열·키워드 외의 신호(예: Place 메타데이터)를 쓰게 되면, 그 신호가
    준비되는 시점에 맞춰 게이트 위치를 다시 정한다.
  - 문서(`OFFTOPIC-CONFIDENCE-GATE-HANDOFF-DRAFT.md`)가 가리켰던
    `.claude/handoff/SEARCH-UPGRADE-HANDOFF.md`는 이 레포·`ai`·`docs` 어디에도 없었다 — 이
    결정과 구현은 그 문서 대신 P49 제안서와 `search-upgrade` 브랜치의 실제 코드를 근거로
    삼았다는 것을 남겨 둔다.
