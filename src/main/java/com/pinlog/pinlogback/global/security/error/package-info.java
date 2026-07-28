/**
 * Security 필터 체인이 직접 만드는 오류 응답.
 *
 * <p>필터 체인은 {@code DispatcherServlet} 이전에 동작해 {@code @RestControllerAdvice}를 타지
 * 않는다. 그대로 두면 401·403만 본문 없이 나가 응답 계약이 깨지므로, 여기서 공통 envelope를
 * 직접 만든다(BD-06, error-handling.md).
 *
 * <p>패키지로 가른 이유는 이것이 <b>인증 로직이 아니라 응답 형식</b>의 관심사이기 때문이다.
 * 인증 방식이 바뀌어도 이 세 클래스는 그대로다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-27). <b>패키지 애노테이션은 하위
 * 패키지로 상속되지 않으므로</b> 상위 {@code global.security}의 선언에 기대면 안 된다.
 */
@NullMarked
package com.pinlog.pinlogback.global.security.error;

import org.jspecify.annotations.NullMarked;
