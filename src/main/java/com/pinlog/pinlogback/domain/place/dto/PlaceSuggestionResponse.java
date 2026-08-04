package com.pinlog.pinlogback.domain.place.dto;

import java.math.BigDecimal;
import java.util.List;

import com.pinlog.pinlogback.domain.ai.client.AiPlaceSuggestionResponse;

public record PlaceSuggestionResponse(
	String requestId,
	List<Candidate> candidates,
	List<SuggestionWarning> warnings
) {
	public static PlaceSuggestionResponse from(AiPlaceSuggestionResponse source) {
		return new PlaceSuggestionResponse(
			source.requestId(),
			source.candidates().stream().map(Candidate::from).toList(),
			source.warnings().stream().map(SuggestionWarning::from).toList());
	}

	public record Candidate(String candidateId, Extracted extracted, KakaoSearch kakaoSearch) {
		private static Candidate from(AiPlaceSuggestionResponse.Candidate source) {
			return new Candidate(source.candidateId(), Extracted.from(source.extracted()),
				KakaoSearch.from(source.kakaoSearch()));
		}
	}

	public record Extracted(
		String placeName,
		List<String> regionHints,
		String branchHint,
		List<String> evidence,
		String contextSuggestion
	) {
		private static Extracted from(AiPlaceSuggestionResponse.Extracted source) {
			return new Extracted(source.placeName(), source.regionHints(), source.branchHint(),
				source.evidence(), source.contextSuggestion());
		}
	}

	public record KakaoSearch(String status, String query, List<KakaoPlace> items) {
		private static KakaoSearch from(AiPlaceSuggestionResponse.KakaoSearch source) {
			return new KakaoSearch(source.status(), source.query(),
				source.items().stream().map(KakaoPlace::from).toList());
		}
	}

	public record KakaoPlace(
		String kakaoPlaceId,
		String name,
		String categoryName,
		String address,
		String roadAddress,
		String phone,
		String placeUrl,
		BigDecimal lat,
		BigDecimal lng
	) {
		private static KakaoPlace from(AiPlaceSuggestionResponse.KakaoPlace source) {
			return new KakaoPlace(source.kakaoPlaceId(), source.name(), source.categoryName(),
				source.address(), source.roadAddress(), source.phone(), source.placeUrl(),
				source.lat(), source.lng());
		}
	}

	public record SuggestionWarning(String code, String message, String candidateId) {
		private static SuggestionWarning from(AiPlaceSuggestionResponse.SuggestionWarning source) {
			return new SuggestionWarning(source.code(), source.message(), source.candidateId());
		}
	}
}
