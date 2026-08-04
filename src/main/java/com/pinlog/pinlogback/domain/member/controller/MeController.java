package com.pinlog.pinlogback.domain.member.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.member.dto.MeSummaryResponse;
import com.pinlog.pinlogback.domain.member.dto.WithdrawalStartResponse;
import com.pinlog.pinlogback.domain.member.service.MemberSummaryService;
import com.pinlog.pinlogback.domain.member.service.WithdrawalAuthorizationService;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

/**
 * 내 계정(API 명세 3.5~3.6). context-path(/api/core)는 인프라 고정값이므로 버전 세그먼트만 명시한다.
 *
 * <p>이 경로는 <b>Refresh 쿠키의 {@code Path} 범위 밖</b>이라 Refresh가 전송되지 않는다. 회원 식별은
 * Access 쿠키로 하고, 세션 폐기는 탈퇴가 실제로 확정되는 콜백 처리에서 한다(BD-48).
 */
@RestController
@RequestMapping("/v1/me")
public class MeController {

	private final WithdrawalAuthorizationService withdrawalAuthorizationService;
	private final MemberSummaryService memberSummaryService;

	public MeController(
		WithdrawalAuthorizationService withdrawalAuthorizationService,
		MemberSummaryService memberSummaryService
	) {
		this.withdrawalAuthorizationService = withdrawalAuthorizationService;
		this.memberSummaryService = memberSummaryService;
	}

	/** 마이페이지 요약(API 명세 3.5). 진입 시 1회 호출한다. */
	@GetMapping("/summary")
	public MeSummaryResponse summary(@LoginMember MemberPrincipal me) {
		return memberSummaryService.summarize(me.memberId());
	}

	/**
	 * 회원 탈퇴 <b>시작</b>. 여기서는 아무것도 지우지 않고 공급자 인가 URL만 돌려준다(BD-48).
	 *
	 * <p>지우고 나서 해제할 수 없다 — {@code social_account} 마스킹이 공급자 식별자를 파기하므로
	 * 순서를 뒤집으면 해제 대상을 잃는다. 그리고 해제에 필요한 것은 공급자의 access token인데
	 * 클라이언트가 들고 있는 것은 우리가 서명한 JWT라, 탈퇴 시점에 인가를 한 번 더 받아야 한다.
	 *
	 * <p>쿠키도 여기서 지우지 않는다. 왕복이 끝나기 전에 로그아웃시키면 콜백에서 회원을 식별할
	 * 근거가 사라진다 — 삭제와 쿠키 만료는 콜백 처리로 옮겼다.
	 *
	 * <p>메서드와 경로는 그대로 {@code DELETE /v1/me}다. 클라이언트가 하는 일(탈퇴 요청)이 바뀌지
	 * 않았고, 응답 본문이 생겨 {@code 204}가 {@code 200}이 됐을 뿐이다.
	 */
	@DeleteMapping
	public WithdrawalStartResponse withdraw(@LoginMember MemberPrincipal me) {
		return withdrawalAuthorizationService.start(me.memberId());
	}
}
