package com.pinlog.pinlogback.domain.ai.event;

/**
 * Context 하나가 AI 처리를 기다린다는 사실. 발행 시점에 {@code ai.context_ai_state}의 {@code PENDING}
 * 행이 같은 트랜잭션 안에 이미 들어가 있다.
 *
 * <p><b>엔티티를 담지 않고 식별자만 담는다</b>(AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md}
 * 4.2). 엔티티를 실어 보내면 영속성 컨텍스트 밖에서 지연 로딩이 터지고, 커밋 시점 스냅샷을 담아
 * 두면 무엇이 최신인지 판단할 근거가 사라진다. 리스너가 별도 읽기 트랜잭션에서 본문을 다시 읽는다.
 *
 * @param contextId 처리 대상 Context. 본문의 정체성이며 같은 id로 다른 본문을 보내는 것은 계약 위반이다
 * @param memberId 소유 회원. FastAPI에는 검색 범위 필터값 {@code userId}로 전달된다
 * @param recordId 소속 Record
 */
public record ContextAiRequested(Long contextId, Long memberId, Long recordId) {
}
