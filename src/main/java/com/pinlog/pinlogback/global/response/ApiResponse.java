package com.pinlog.pinlogback.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 응답의 공통 봉투. 성공은 data, 오류는 error만 채운다(API 명세 1.6).
 *
 * <p>성공 응답에는 message 필드를 두지 않는다. 사람이 읽을 문구가 필요하면 data 안의 도메인 필드로 표현한다.
 * null 필드는 직렬화에서 생략되므로 성공 응답에 error 키가, 오류 응답에 data 키가 나타나지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
	boolean success,
	T data,
	ErrorResponse error
) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static <T> ApiResponse<T> fail(ErrorResponse error) {
		return new ApiResponse<>(false, null, error);
	}
}
