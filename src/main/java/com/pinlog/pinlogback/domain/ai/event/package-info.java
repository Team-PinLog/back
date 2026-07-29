/**
 * Core 트랜잭션이 커밋된 뒤에야 해야 할 AI 연동 작업을 알리는 도메인 이벤트.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-29과 같은 이유·같은 단위).
 */
@NullMarked
package com.pinlog.pinlogback.domain.ai.event;

import org.jspecify.annotations.NullMarked;
