/**
 * 인증·인가 지원 타입. 책임별로 하위 패키지에 나뉘어 있다.
 *
 * <table border="1">
 *   <caption>하위 패키지</caption>
 *   <tr><th>패키지</th><th>책임</th><th>언제 도는가</th></tr>
 *   <tr><td>{@code oauth}</td><td>공급자와의 OAuth2 흐름 (OAuth 클라이언트 역할)</td>
 *       <td>로그인 진입·콜백</td></tr>
 *   <tr><td>{@code token}</td><td>세션 토큰 서명·검증과 쿠키 전달 (BFF 역할)</td>
 *       <td>로그인 성공·재발급</td></tr>
 *   <tr><td>{@code authentication}</td><td>요청을 인증 주체로 변환, principal 계약 (리소스 서버 역할)</td>
 *       <td>모든 요청</td></tr>
 *   <tr><td>{@code error}</td><td>필터 체인이 직접 만드는 401·403 응답</td>
 *       <td>인증·인가 실패</td></tr>
 * </table>
 *
 * <p>의존은 한 방향이다 — {@code oauth}와 {@code authentication}이 {@code token}을 쓰고,
 * {@code error}는 어느 쪽도 쓰지 않는다. 반대 방향 참조가 생기면 경계가 잘못된 것이다.
 *
 * <p>{@code CsrfCookieFilter}만 이 패키지에 남아 있다. CSRF는 세션 토큰과 다른 관심사라
 * {@code token}에 넣으면 이름이 거짓말이 되고, 나머지 셋에도 속하지 않는다. 억지로 끼워 넣는
 * 대신 루트에 둔다.
 *
 * <p><b>하위 패키지에 클래스를 추가할 때</b>: 패키지 애노테이션은 상속되지 않으므로 이
 * 선언({@link org.jspecify.annotations.NullMarked})이 하위 패키지에 적용되지 않는다. 각
 * 하위 패키지가 자기 {@code package-info}에 따로 선언하고 있다(BD-27).
 */
@NullMarked
package com.pinlog.pinlogback.global.security;

import org.jspecify.annotations.NullMarked;
