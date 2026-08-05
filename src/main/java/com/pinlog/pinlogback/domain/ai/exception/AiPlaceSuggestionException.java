package com.pinlog.pinlogback.domain.ai.exception;

import com.pinlog.pinlogback.global.exception.BusinessException;
import com.pinlog.pinlogback.global.exception.ErrorCode;

public class AiPlaceSuggestionException extends BusinessException {

	private AiPlaceSuggestionException(ErrorCode errorCode) {
		super(errorCode);
	}

	public static AiPlaceSuggestionException invalidImageCount() {
		return new AiPlaceSuggestionException(ErrorCode.INVALID_IMAGE_COUNT);
	}

	public static AiPlaceSuggestionException invalidImage() {
		return new AiPlaceSuggestionException(ErrorCode.INVALID_IMAGE);
	}

	public static AiPlaceSuggestionException imageTooLarge() {
		return new AiPlaceSuggestionException(ErrorCode.IMAGE_TOO_LARGE);
	}

	public static AiPlaceSuggestionException unsupportedMediaType() {
		return new AiPlaceSuggestionException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
	}

	public static AiPlaceSuggestionException upstreamError() {
		return new AiPlaceSuggestionException(ErrorCode.PLACE_SUGGESTION_UPSTREAM_ERROR);
	}

	public static AiPlaceSuggestionException unavailable() {
		return new AiPlaceSuggestionException(ErrorCode.PLACE_SUGGESTION_UNAVAILABLE);
	}

	public static AiPlaceSuggestionException busy() {
		return new AiPlaceSuggestionException(ErrorCode.PLACE_SUGGESTION_BUSY);
	}

	public static AiPlaceSuggestionException timeout() {
		return new AiPlaceSuggestionException(ErrorCode.PLACE_SUGGESTION_TIMEOUT);
	}

	public static AiPlaceSuggestionException internalError() {
		return new AiPlaceSuggestionException(ErrorCode.INTERNAL_ERROR);
	}
}
