/**
 * 세션 토큰의 서명·검증과 브라우저 전달. <b>우리가 BFF로서 하는 일</b>이다.
 *
 * <p>공급자 인증이 끝난 뒤부터가 이 패키지의 범위다 — 키를 공급하고({@code JwtKeyProvider}),
 * RS256으로 서명·검증하고({@code JwtTokenProvider}), 계약된 속성의 쿠키로 실어 보낸다
 * ({@code AuthCookies}). 근거는 BD-21·BD-29.
 *
 * <p>여기서 만든 토큰을 <b>읽어서</b> 인증 주체로 바꾸는 일은 {@code ..security.authentication}이
 * 맡는다. 발급과 검증을 한 패키지에 둔 이유는 서명 키를 공유하기 때문이고, 읽는 쪽을 가른 이유는
 * 그쪽이 요청마다 도는 필터 관심사라서다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-27). <b>패키지 애노테이션은 하위
 * 패키지로 상속되지 않으므로</b> 상위 {@code global.security}의 선언에 기대면 안 된다.
 */
@NullMarked
package com.pinlog.pinlogback.global.security.token;

import org.jspecify.annotations.NullMarked;
