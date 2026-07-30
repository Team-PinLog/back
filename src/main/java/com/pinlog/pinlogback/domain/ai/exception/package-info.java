/**
 * FastAPI 연동 실패를 도메인 오류로 옮기는 예외.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-29과 같은 이유·같은 단위).
 * {@code @NullMarked}는 하위 패키지로 상속되지 않으므로 상위 {@code domain.ai}에 선언돼 있어도
 * 여기에 다시 적어야 한다.
 */
@NullMarked
package com.pinlog.pinlogback.domain.ai.exception;

import org.jspecify.annotations.NullMarked;
