package com.pinlog.pinlogback.domain.member.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.member.dto.MeSummaryResponse;
import com.pinlog.pinlogback.domain.member.service.MemberSummaryService;
import com.pinlog.pinlogback.domain.member.service.MemberWithdrawalService;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;
import com.pinlog.pinlogback.global.security.token.AuthCookies;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 내 계정(API 명세 3.5~3.6). context-path(/api/core)는 인프라 고정값이므로 버전 세그먼트만 명시한다.
 *
 * <p>이 경로는 <b>Refresh 쿠키의 {@code Path} 범위 밖</b>이라 Refresh가 전송되지 않는다. 따라서
 * Access 쿠키로 회원을 식별하고, 그 회원의 Refresh를 서버 쪽에서 전부 폐기한다 — 탈퇴는 모든
 * 기기에서 즉시 끊겨야 하므로 전체 폐기가 의도된 동작이다(08 §3.6).
 */
@RestController
@RequestMapping("/v1/me")
public class MeController {

	private final MemberWithdrawalService memberWithdrawalService;
	private final MemberSummaryService memberSummaryService;
	private final AuthCookies authCookies;

	public MeController(
		MemberWithdrawalService memberWithdrawalService,
		MemberSummaryService memberSummaryService,
		AuthCookies authCookies
	) {
		this.memberWithdrawalService = memberWithdrawalService;
		this.memberSummaryService = memberSummaryService;
		this.authCookies = authCookies;
	}

	/** 마이페이지 요약(API 명세 3.5). 진입 시 1회 호출한다. */
	@GetMapping("/summary")
	public MeSummaryResponse summary(@LoginMember MemberPrincipal me) {
		return memberSummaryService.summarize(me.memberId());
	}

	/**
	 * 회원 탈퇴. 본문이 없는 {@code 204}이므로 공통 envelope가 적용되지 않는다
	 * ({@code ApiResponseBodyAdvice}가 {@code null} body를 그대로 통과시킨다).
	 *
	 * <p>쿠키 만료를 서비스가 아니라 여기서 한다 — 트랜잭션이 성공했을 때만 지워야 하고, 실패하면
	 * 예외가 이 지점에 도달하지 않는다. 반대로 서비스 안에서 지우면 롤백된 요청이 클라이언트를
	 * 로그아웃시킨다.
	 */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void withdraw(@LoginMember MemberPrincipal me, HttpServletResponse response) {
		memberWithdrawalService.withdraw(me.memberId());
		authCookies.clear(response);
	}
}
