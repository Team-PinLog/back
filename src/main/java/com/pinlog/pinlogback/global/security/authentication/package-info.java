/**
 * 들어온 요청을 인증 주체로 바꾸는 일. <b>우리가 리소스 서버로서 하는 일</b>이다.
 *
 * <p>요청마다 Access 쿠키를 읽어 SecurityContext를 채우고({@code JwtAuthenticationFilter}),
 * 컨트롤러가 그 결과를 {@code @LoginMember MemberPrincipal}로 받는다. principal 계약
 * (authentication.md 2)이 사는 곳이다.
 *
 * <p>토큰을 <b>만드는</b> 쪽({@code ..security.token})과 가른 이유: 발급은 로그인 시점에 한 번
 * 일어나고 여기는 모든 요청에서 돈다. 수명과 호출 빈도가 다르면 같이 두지 않는다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-27). <b>패키지 애노테이션은 하위
 * 패키지로 상속되지 않으므로</b> 상위 {@code global.security}의 선언에 기대면 안 된다.
 */
@NullMarked
package com.pinlog.pinlogback.global.security.authentication;

import org.jspecify.annotations.NullMarked;
