# 조회 응답 3곳에 Record·Collection 키워드를 채웠다

- **날짜**: 2026-08-01
- **추적**: Jira 작업
- **관련**: [BI-36](../implements/BI-36-2026-08-01-keyword-exposure.md) · [back#145](https://github.com/Team-PinLog/back/issues/145) · [#147](https://github.com/Team-PinLog/back/pull/147) · [BD-18](../decisions/BD-18-keyword-preset-and-visibility.md)

키워드를 실제로 채우는 응답이 **AI 검색 하나뿐**이었고, Record 상세·타인 Record 카드·책장 목록(§9.3·§8.1)은 `List.of()` 고정이라 "AI가 채우기 전까지 빈 배열"이 사실상 영구 빈 배열이었다. AI 몫(FastAPI가 `ai.context_keyword`를 채움)은 끝나 있었고, 빠져 있던 것은 백엔드 몫인 읽기 조인이다(BD-16이 백엔드의 권리이자 의무로 명시).

`ContextKeywordRepository`에 공개용 집계 둘(`findKeywordsPublic`·`findCollectionKeywordsPublic`)을 신설했다. **소유자는 PUBLIC+PRIVATE_ONLY, 타인은 PUBLIC만**이라는 Visibility 경계(04 §2)를 메서드 단위로 갈라 호출부가 조건을 고를 여지를 없앴고, 필터는 전부 WHERE 절에 있다(05 §14.4). 티켓 초안의 "세 곳 모두 PUBLIC만"은 소유자 상세에서 04 §2 표와 어긋나 정정했다.

**생성 응답은 명시적으로 비운다** — CONTEXT_ADDED로 기존 Record에 완료된 키워드가 있어도 생성 응답에 싣지 않는 것이 명세 1.3이고, 가드 테스트가 그 상태를 고정한다. Feed의 `FeedKeywordRepository`와는 합치지 않았다 — 그쪽은 점수용 code·가중치 분포(AI 파트 계약), 이쪽은 표시용 display_name 목록이라 반환 계약이 다르고, 덕분에 Feed의 code 노출 건(#146)과 순서 의존이 없다.

검증은 운영 AI 없이 테스트가 `ai.context_keyword`를 직접 넣고 COMPLETED로 올려 수행했다. 파이썬 편집(`write_text`)이 만든 CRLF로 checkstyle이 깨진 것을 LF 변환으로 수습했다 — `windows-crlf-checkstyle-trap`의 변종(이번엔 autocrlf가 아니라 파이썬이 원인). `clean check` 454개 통과.
