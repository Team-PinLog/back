package com.pinlog.pinlogback.domain.sample;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.global.response.ApiResponse;

/** Advice 판정(도메인 패키지)을 검증하기 위한 테스트 전용 컨트롤러. 운영 코드가 아니다. */
@RestController
public class EnvelopeTestController {

	public record Payload(String name) {
	}

	@GetMapping("/test-envelope/dto")
	public Payload dto() {
		return new Payload("pinlog");
	}

	@GetMapping("/test-envelope/already-wrapped")
	public ApiResponse<Payload> alreadyWrapped() {
		return ApiResponse.ok(new Payload("pinlog"));
	}

	/**
	 * 선언 타입이 {@code ResponseEntity}라서 "이미 envelope인가" 판정이 헷갈리는 경우.
	 * 런타임 advice는 실제 body를 보고 감싸지 않는데, 문서 생성 쪽이 선언 타입만 보고 판정하면
	 * 여기서 결론이 갈려 문서가 실제 응답과 달라진다(S15P11A705-85).
	 */
	@GetMapping("/test-envelope/entity-wrapped")
	public ResponseEntity<ApiResponse<Payload>> entityWrapped() {
		return ResponseEntity.ok(ApiResponse.ok(new Payload("pinlog")));
	}

	@GetMapping("/test-envelope/void")
	public void noContent() {
	}

	@GetMapping("/test-envelope/no-content")
	public ResponseEntity<Void> noContentEntity() {
		return ResponseEntity.noContent().build();
	}
}
