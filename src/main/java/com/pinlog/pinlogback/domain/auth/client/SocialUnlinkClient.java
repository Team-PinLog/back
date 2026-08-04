package com.pinlog.pinlogback.domain.auth.client;

import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * 공급자에게 연결 해제를 요청한다(BD-48 §①).
 *
 * <p>세 공급자 모두 access token 하나로 해제할 수 있어 호출부에는 분기가 없다. 요청 형태는
 * 제각각이라 구현을 나눈다 — Kakao는 Bearer 헤더, Google·Naver는 form 본문이고 Naver는 클라이언트
 * 인증까지 요구한다.
 *
 * <p>토큰은 <b>탈퇴 시점 인가 왕복으로 방금 받은 것</b>이다. 보관하지 않는다.
 */
public interface SocialUnlinkClient {

	/** 이 구현이 담당하는 공급자. 호출부가 이 값으로 고른다. */
	SocialProvider provider();

	/**
	 * @param accessToken 공급자가 방금 발급한 access token
	 * @throws SocialUnlinkException 해제가 확인되지 않았을 때. 삼키면 해제 없이 회원이 지워진다
	 */
	void unlink(String accessToken);
}
