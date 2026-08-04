package com.pinlog.pinlogback.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청 형식입니다."),
	// 미인증. 타인 소유 자원 접근은 존재를 숨기기 위해 403이 아니라 404를 쓴다(08 §1.2).
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	// CSRF 토큰 누락·불일치 전용이다(08 §1.7).
	FORBIDDEN(HttpStatus.FORBIDDEN, "요청이 거부되었습니다."),
	SELF_FOLLOW_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "자기 자신의 책장은 팔로우할 수 없습니다."),
	DUPLICATE_FOLLOW(HttpStatus.CONFLICT, "이미 팔로우한 책장입니다."),
	DELETE_CONFIRMATION_REQUIRED(HttpStatus.CONFLICT, "삭제 확인이 필요합니다."),
	// 한 번의 인가 왕복은 한 공급자만 해제한다. 계정이 여럿이면 나머지가 마스킹으로 영구히
	// 못 끊기므로 시작하지 않는다(BD-48 §⑥). 500이 아니라 409인 이유는 이것이 터진 것이 아니라
	// 막은 것이기 때문이다 — 500이면 운영 알림이 버그로 운다.
	WITHDRAWAL_NOT_SUPPORTED(HttpStatus.CONFLICT, "지금 계정 구성으로는 탈퇴를 진행할 수 없습니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
	// AI 검색 실패 둘. 빈 결과로 치환하지 않는 것이 계약이다 — 빈 결과는 "일치하는 기록이 없음"으로
	// 보여 장애·설정 오류를 숨긴다(ai 레포 docs/spec/model-profile.md 3.1). 자연어 검색에만 해당하며
	// 저장·조회·발행에는 영향이 없다(AI 설계 응답 조립 6.4).
	//
	// 둘 다 503이지만 code를 가른다. 사람이 해야 할 일이 정반대이기 때문이다 — 불일치는 배포 설정을
	// 고쳐야 풀리고(재시도해도 그대로), 그 외는 대개 기다리면 낫는다. 한 code로 뭉치면 운영자가
	// 설정 오류를 상대 장애로 읽는다. 응답에는 code만 싣고 Profile 값 자체는 로그에만 남긴다.
	SEARCH_PROFILE_MISMATCH(HttpStatus.SERVICE_UNAVAILABLE, "검색 설정이 일치하지 않아 검색할 수 없습니다."),
	SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "검색을 일시적으로 사용할 수 없습니다."),
	// 인증 필터가 탈퇴 여부를 확인하려 DB를 읽다 실패했을 때다(BD-47). 401을 쓰지 않는 이유가 여기
	// 있다 — 자격증명은 멀쩡하고 우리가 확인을 못 한 것이라, 401로 내보내면 클라이언트가 재로그인을
	// 유도해 DB 순단이 전면 로그아웃으로 번진다. readiness가 같은 원인에 이미 503을 쓴다(BD-28).
	AUTH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 인증을 확인할 수 없습니다."),
	INVALID_IMAGE_COUNT(HttpStatus.BAD_REQUEST,
		"\uC774\uBBF8\uC9C0\uB294 \uC815\uD655\uD788 1\uC7A5\uB9CC \uC5C5\uB85C\uB4DC\uD560 \uC218 "
			+ "\uC788\uC2B5\uB2C8\uB2E4."),
	INVALID_IMAGE(HttpStatus.BAD_REQUEST,
		"\uC77D\uC744 \uC218 \uC788\uB294 \uC774\uBBF8\uC9C0\uAC00 \uC544\uB2D9\uB2C8\uB2E4."),
	IMAGE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE,
		"\uC5C5\uB85C\uB4DC \uAC00\uB2A5\uD55C \uC774\uBBF8\uC9C0 \uC6A9\uB7C9\uC744 "
			+ "\uCD08\uACFC\uD588\uC2B5\uB2C8\uB2E4."),
	PLACE_SUGGESTION_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY,
		"\uC7A5\uC18C \uC81C\uC548 \uC11C\uBE44\uC2A4\uB97C \uCC98\uB9AC\uD560 \uC218 "
			+ "\uC5C6\uC2B5\uB2C8\uB2E4."),
	PLACE_SUGGESTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
		"\uC7A5\uC18C \uC81C\uC548 \uC11C\uBE44\uC2A4\uB97C \uC77C\uC2DC\uC801\uC73C\uB85C "
			+ "\uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4."),
	PLACE_SUGGESTION_BUSY(HttpStatus.SERVICE_UNAVAILABLE,
		"\uD604\uC7AC \uB2E4\uB978 \uC7A5\uC18C \uC774\uBBF8\uC9C0\uB97C \uBD84\uC11D\uD558\uACE0 "
			+ "\uC788\uC2B5\uB2C8\uB2E4."),
	PLACE_SUGGESTION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT,
		"\uC7A5\uC18C \uBD84\uC11D \uC2DC\uAC04\uC774 \uCD08\uACFC\uB418\uC5C8\uC2B5\uB2C8\uB2E4."),

	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 메서드입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다.");

	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getCode() {
		return name();
	}

	public String getMessage() {
		return message;
	}
}
