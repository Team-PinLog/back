package com.pinlog.pinlogback.domain.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

/**
 * principal 계약(쿠키 → SecurityContext → {@code @LoginMember})이 끝까지 이어지는지 확인하기 위한
 * 테스트 전용 컨트롤러. 운영 코드가 아니다.
 *
 * <p>도메인 엔드포인트가 아직 이 브랜치에 없어서 둔다. 도메인 API가 병합되면 그쪽 테스트가
 * 같은 경로를 덮으므로 이 컨트롤러는 지워도 된다.
 */
@RestController
public class AuthenticatedTestController {

	public record Me(Long memberId) {
	}

	@GetMapping("/v1/test-authenticated/me")
	public Me me(@LoginMember MemberPrincipal principal) {
		return new Me(principal.memberId());
	}
}
