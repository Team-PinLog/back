package com.pinlog.pinlogback.domain.feed.repository;

/**
 * 응답에 실을 Keyword 하나의 표시 정보(feed-recommendation 3.7.1).
 *
 * <p>점수 계산은 {@code code}로 하고 여기 담긴 값은 <b>표시와 표시 정렬에만</b> 쓴다. 셋을 한
 * 번에 들고 오는 이유는 표시값 조회가 이미 {@code ai.keyword_preset}을 한 번 읽기 때문이다 —
 * 축이나 id를 따로 조회하면 그만큼 왕복이 는다(feed-tests N8).
 *
 * @param presetId {@code keyword_preset.id}. UNIQUE 불변 정수라 <b>동점 규칙의 최종 결정자</b>다.
 *     표시값을 동점 규칙으로 쓰면 라벨을 고친 날 카드의 Keyword 구성이 바뀐다(S15P11A705-252가
 *     점수 계산의 키를 {@code code}로 못 박은 것과 같은 이유). id는 축 블록
 *     (1xx {@code COMPANION} / 2xx {@code ACTIVITY} / 3xx {@code ATMOSPHERE} /
 *     4xx {@code SITUATION})이 들어 있어 동점일 때 축 순서로 정렬되는 부수 효과까지 있다
 * @param displayName 화면에 그대로 그려지는 라벨. {@code code}는 노출하지 않는다(08 §6.1)
 * @param category 표시 정렬의 <b>1순위</b>인 축. 「누구와 · 무엇을 · 어떤 분위기 · 어떤 상황」
 */
public record FeedKeywordLabel(int presetId, String displayName, String category) {
}
