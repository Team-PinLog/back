/**
 * 인증 도메인 예외.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-29과 같은 이유·같은 단위).
 * 지금은 nullable 지점이 없지만 {@code domain/auth}의 나머지 패키지를 모두 마킹했으므로 함께
 * 선언한다 — 도메인 안에서 마킹 여부가 갈리면 여기에 클래스를 추가하는 사람이 규칙을 놓친다.
 */
@NullMarked
package com.pinlog.pinlogback.domain.auth.exception;

import org.jspecify.annotations.NullMarked;
