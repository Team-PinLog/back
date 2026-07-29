package com.pinlog.pinlogback.domain.auth.exception;

import com.pinlog.pinlogback.global.exception.BusinessException;
import com.pinlog.pinlogback.global.exception.ErrorCode;

/**
 * 지원하지 않는 소셜 공급자로 로그인을 시도한 경우.
 *
 * <p>404로 응답한다. 존재하지 않는 경로와 구분할 실익이 없고, 어떤 provider를 지원하는지는
 * API 명세 3.1이 이미 공개하고 있다.
 */
public class UnsupportedSocialProviderException extends BusinessException {

	public UnsupportedSocialProviderException(String provider) {
		super(ErrorCode.RESOURCE_NOT_FOUND, "지원하지 않는 소셜 로그인 공급자입니다: " + provider);
	}
}
