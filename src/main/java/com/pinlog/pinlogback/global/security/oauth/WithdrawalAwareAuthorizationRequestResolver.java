package com.pinlog.pinlogback.global.security.oauth;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 인가 왕복이 로그인인지 탈퇴인지 진입에서 확정한다(BD-48 §②·§③).
 *
 * <p><b>티켓이 없으면 로그인이다.</b> 위임 결과를 그대로 돌려준다.
 *
 * <p><b>티켓이 있으면 서명을 검증하고 대상 회원을 {@code attributes}에 싣는다.</b> 인가 진입은
 * 브라우저 내비게이션이라 {@code GET}이고, 의도를 파라미터로만 받으면 악성 사이트가 피해자를 그
 * 경로로 유도해 계정 삭제까지 이르게 할 수 있다 — {@code DELETE /v1/me}에 CSRF를 걸어도 뒤 단계가
 * GET이라 우회된다. 서명 키가 없는 공격자는 티켓을 만들 수 없다.
 *
 * <p>회원 식별자를 {@code attributes}에 두는 이유는 <b>왕복 중 Access(30분)가 만료될 수 있어서</b>다.
 * 콜백에서 쿠키로 다시 식별하면 그때 탈퇴를 완료할 수 없다. {@code attributes}는 인가 요청 객체째로
 * 우리 쿠키에 보관돼 클라이언트에 나가지 않으므로, {@code state} 문자열에 인코딩할 때 생기는
 * tampering·swapping 문제(RFC 9700)를 피한다.
 *
 * <p><b>티켓이 있는데 유효하지 않으면 인가 요청을 만들지 않는다.</b> 없는 것으로 취급하면 탈퇴
 * 시도가 조용히 로그인으로 바뀐다. 그 결과는 404이며, 정상 흐름은 여기에 닿지 않는다 — 티켓 수명
 * 5분이 덮는 구간은 {@code DELETE /v1/me} 응답과 브라우저 이동 사이뿐이고, 사용자가 공급자 화면에
 * 머무는 시간은 이 뒤에 온다.
 */
@Slf4j
public class WithdrawalAwareAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	/** 진입 URL이 티켓을 싣는 파라미터. {@code WithdrawalAuthorizationService}가 같은 이름을 쓴다. */
	public static final String TICKET_PARAMETER = "ticket";

	/**
	 * 탈퇴 대상 회원을 담는 {@code attributes} 키. 이 키가 있으면 콜백은 로그인이 아니라 탈퇴다.
	 *
	 * <p><b>값은 문자열이다.</b> 인가 요청은 JSON으로 쿠키에 담기는데, 그 경로의
	 * {@code PolymorphicTypeValidator}가 {@code java.lang.Long} 같은 임의 타입의 복원을 거부한다.
	 * Spring 자신이 {@code attributes}에 넣는 값들({@code registration_id}·PKCE
	 * {@code code_verifier})도 모두 문자열이다. 검증기를 느슨하게 푸는 대신 값을 맞춘다.
	 */
	public static final String WITHDRAWAL_MEMBER_ID = "withdrawal_member_id";

	/**
	 * 콜백 처리에서 이 왕복이 누구의 탈퇴였는지 읽는다.
	 *
	 * <p>{@code attributes} 키의 소유자가 이 클래스이므로 읽는 방법도 여기 둔다. 성공·실패 두
	 * 핸들러가 각자 키를 꺼내면 키 이름이 한 곳에서만 바뀌는 사고가 생긴다.
	 *
	 * @return 로그인 왕복이거나 인가 요청이 소비되지 않았으면 빈 값
	 */
	public static Optional<Long> withdrawalMemberId(HttpServletRequest request) {
		return CookieOAuth2AuthorizationRequestRepository.consumedAuthorizationRequest(request)
			.map(authorizationRequest -> authorizationRequest.getAttributes().get(WITHDRAWAL_MEMBER_ID))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.flatMap(WithdrawalAwareAuthorizationRequestResolver::parseMemberId);
	}

	/** 값을 우리가 넣지만 담기는 곳이 쿠키라, 숫자가 아닌 값이 돌아오면 탈퇴가 아닌 것으로 본다. */
	private static Optional<Long> parseMemberId(String value) {
		try {
			return Optional.of(Long.valueOf(value));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private final OAuth2AuthorizationRequestResolver delegate;
	private final JwtTokenProvider tokenProvider;

	public WithdrawalAwareAuthorizationRequestResolver(
		OAuth2AuthorizationRequestResolver delegate,
		JwtTokenProvider tokenProvider
	) {
		this.delegate = delegate;
		this.tokenProvider = tokenProvider;
	}

	@Override
	public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		return withWithdrawalIntent(delegate.resolve(request), request);
	}

	@Override
	public @Nullable OAuth2AuthorizationRequest resolve(
		HttpServletRequest request, String clientRegistrationId) {
		return withWithdrawalIntent(delegate.resolve(request, clientRegistrationId), request);
	}

	private @Nullable OAuth2AuthorizationRequest withWithdrawalIntent(
		@Nullable OAuth2AuthorizationRequest resolved, HttpServletRequest request) {
		String ticket = request.getParameter(TICKET_PARAMETER);
		if (resolved == null || ticket == null) {
			return resolved;
		}

		Optional<Long> memberId = tokenProvider.parseWithdrawalTicket(ticket);
		if (memberId.isEmpty()) {
			// 만료·위조·용도 불일치를 구분해 알리지 않는다. 구분해 주면 공격자에게 정보를 준다.
			log.warn("withdrawal authorization rejected: invalid ticket");
			return null;
		}

		return OAuth2AuthorizationRequest.from(resolved)
			.attributes(attributes ->
				attributes.put(WITHDRAWAL_MEMBER_ID, String.valueOf(memberId.get())))
			.build();
	}
}
