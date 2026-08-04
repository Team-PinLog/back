package com.pinlog.pinlogback.domain.member.dto;

/**
 * 탈퇴 요청의 응답(08 §3.6.1). 아직 아무것도 지우지 않았고, 클라이언트가 이 주소로 이동해야
 * 흐름이 이어진다.
 *
 * <p>값이 우리 경로일 수도 공급자의 절대 URL일 수도 있으므로 <b>클라이언트는 해석하지 않는다.</b>
 * 지금 구현은 우리 인가 진입 경로를 돌려주고, 공급자 URL 조립은 Spring의 인가 요청 필터에 맡긴다.
 */
public record WithdrawalStartResponse(String authorizationUrl) {
}
