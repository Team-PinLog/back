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
