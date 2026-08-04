package com.pinlog.pinlogback.domain.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pinlog.ai.place-suggestion")
public record AiPlaceSuggestionProperties(
	Duration connectTimeout,
	Duration readTimeout,
	int maxConcurrentRequests
) {
	public AiPlaceSuggestionProperties {
		if (maxConcurrentRequests < 1) {
			throw new IllegalArgumentException("place suggestion concurrency must be positive");
		}
	}
}
