/**
 * 공급자와의 OAuth2 Authorization Code 흐름. <b>우리가 OAuth 클라이언트로서 하는 일</b>이다.
 *
 * <p>인가 요청을 만들어 공급자로 보내고(state·PKCE verifier를 쿠키에 담아 왕복시킨다), 돌아온
 * 콜백을 받아 회원을 확정한 뒤 세션을 연다. 여기까지가 공급자와의 대화이고, 그 다음 — 발급한
 * 세션을 브라우저에 실어 보내는 일 — 은 {@code ..security.token}이 맡는다.
 *
 * <p>세 클래스 모두 Spring Security OAuth2 Client의 확장점 구현이다. 경계가 프레임워크 쪽에
 * 이미 그어져 있어 응집이 자연스럽다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-27). <b>패키지 애노테이션은 하위
 * 패키지로 상속되지 않으므로</b> 상위 {@code global.security}의 선언에 기대면 안 된다.
 */
@NullMarked
package com.pinlog.pinlogback.global.security.oauth;

import org.jspecify.annotations.NullMarked;
