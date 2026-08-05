package com.pinlog.pinlogback.domain.collection.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Collection 제목·표지 수정 요청(API 명세 7.4). 두 필드 모두 선택이되 최소 하나는 있어야 하고,
 * <b>보내지 않았거나 null인 필드는 기존 값을 유지한다</b> — Jackson이 String에서 "필드 부재"와
 * "null 명시"를 구분하지 못하므로 null을 제거로 해석하면 제목만 고칠 때 표지가 지워진다.
 * 표지 제거는 MVP에서 제공하지 않는다.
 */
public record CollectionUpdateRequest(
	@Size(max = 20)
	@Pattern(regexp = "(?s).*\\S.*", message = "제목은 공백일 수 없습니다")
	String title,

	@Size(max = 300)
	@Pattern(regexp = COVER_IMAGE_URL_PATTERN, message = "이미지 서비스 최종본 경로(/image/files/*.webp)만 저장할 수 있습니다")
	String coverImageUrl
) {

	/**
	 * 이미지 서비스 최종본의 같은 origin 상대 경로만 허용한다(API 명세 7.4). 프론트가 보낸 값을
	 * 그대로 믿으면 임의 문자열·외부 URL이 표지로 저장된다.
	 */
	public static final String COVER_IMAGE_URL_PATTERN = "^/image/files/[A-Za-z0-9._-]+\\.webp$";

	@AssertTrue(message = "수정할 필드가 최소 하나 필요합니다")
	public boolean isAnyFieldPresent() {
		return title != null || coverImageUrl != null;
	}
}
