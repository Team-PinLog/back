package com.pinlog.pinlogback.domain.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 문자열 검색 병합(P49 §4·§5) 설정.
 *
 * <p>{@code enabled}의 기본값이 {@code false}인 것이 검색 고도화 트랙의 안전장치다 — 모든 신규
 * 신호는 끈 상태가 현행과 동일해야 하고(P49 §7 기준 4), 켜는 것은 검증 게이트 통과 뒤의 결정이다.
 * 운영 중 문제가 나면 이 플래그로 즉시 현행 검색으로 되돌린다.
 *
 * @param enabled 문자열 검색 병합을 켤지. 꺼져 있으면 문자열 조회 자체가 없다
 * @param wordQueryMaxChars 단어형 질의의 최대 글자 수. ai 레포의
 *     {@code SEARCH_WORD_QUERY_MAX_CHARS}와 같은 값·같은 의미여야 한다 — 두 값이 어긋나면
 *     「단어형」의 정의가 파트마다 달라져 게이트(단어형 한정, I54)가 절반만 켜진다
 */
@ConfigurationProperties("pinlog.search.lexical")
public record LexicalSearchProperties(
	boolean enabled,
	int wordQueryMaxChars
) {
}
