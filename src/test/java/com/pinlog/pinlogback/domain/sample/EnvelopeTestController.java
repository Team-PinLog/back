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

	@GetMapping("/test-envelope/void")
	public void noContent() {
	}

	@GetMapping("/test-envelope/no-content")
	public ResponseEntity<Void> noContentEntity() {
		return ResponseEntity.noContent().build();
	}
}
