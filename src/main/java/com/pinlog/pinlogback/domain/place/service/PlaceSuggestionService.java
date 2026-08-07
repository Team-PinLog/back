package com.pinlog.pinlogback.domain.place.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.pinlog.pinlogback.domain.ai.client.AiPlaceSuggestionClient;
import com.pinlog.pinlogback.domain.ai.exception.AiPlaceSuggestionException;
import com.pinlog.pinlogback.domain.place.dto.PlaceSuggestionResponse;

@Service
public class PlaceSuggestionService {

	private static final long INITIAL_MAX_IMAGE_BYTES = 10L * 1024 * 1024;
	private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/png", "image/jpeg");

	private final AiPlaceSuggestionClient aiPlaceSuggestionClient;

	public PlaceSuggestionService(AiPlaceSuggestionClient aiPlaceSuggestionClient) {
		this.aiPlaceSuggestionClient = aiPlaceSuggestionClient;
	}

	public PlaceSuggestionResponse suggest(List<MultipartFile> images) {
		if (images == null || images.size() != 1) {
			throw AiPlaceSuggestionException.invalidImageCount();
		}
		MultipartFile image = images.get(0);
		if (image.isEmpty()) {
			throw AiPlaceSuggestionException.invalidImage();
		}
		if (image.getSize() > INITIAL_MAX_IMAGE_BYTES) {
			throw AiPlaceSuggestionException.imageTooLarge();
		}
		if (!SUPPORTED_CONTENT_TYPES.contains(image.getContentType())) {
			throw AiPlaceSuggestionException.unsupportedMediaType();
		}
		return PlaceSuggestionResponse.from(aiPlaceSuggestionClient.suggest(image));
	}
}
