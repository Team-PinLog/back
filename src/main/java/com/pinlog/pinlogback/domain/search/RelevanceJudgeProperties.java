package com.pinlog.pinlogback.domain.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 검색 결과 LLM 관련도 재판정(4번째 신호) 설정.
 *
 * <p>{@code enabled}의 기본값이 {@code false}인 것은 {@link LexicalSearchProperties}와 같은
 * 이유다 — 신규 신호는 끈 상태가 현행과 동일해야 하고, 켜는 것은 이 구현이 배포·관측된 뒤의
 * 별도 결정이다.
 *
 * @param enabled 관련도 재판정을 켤지. 꺼져 있으면 ai 호출 자체가 없다
 */
@ConfigurationProperties("pinlog.search.relevance-judge")
public record RelevanceJudgeProperties(
	boolean enabled
) {
}
