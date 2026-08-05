package com.pinlog.pinlogback.domain.ai.client;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

public record AiPlaceSuggestionResponse(
	String requestId,
	List<Candidate> candidates,
	List<SuggestionWarning> warnings
) {
	public record Candidate(String candidateId, Extracted extracted, KakaoSearch kakaoSearch) {
	}

	public record Extracted(
		String placeName,
		List<String> regionHints,
		@Nullable String branchHint,
		List<String> evidence,
		@Nullable String contextSuggestion
	) {
	}

	public record KakaoSearch(String status, String query, List<KakaoPlace> items) {
	}

	public record KakaoPlace(
		String kakaoPlaceId,
		String name,
		@Nullable String categoryName,
		String address,
		@Nullable String roadAddress,
		@Nullable String phone,
		@Nullable String placeUrl,
		BigDecimal lat,
		BigDecimal lng
	) {
	}

	public record SuggestionWarning(String code, String message, @Nullable String candidateId) {
	}
}
