package com.pinlog.pinlogback.domain.member.dto;

import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * 마이페이지 요약 응답(API 명세 3.5).
 *
 * <p><b>{@code memberId}를 담지 않는다.</b> 개인 API는 서버가 쿠키로 사용자를 식별하므로
 * 클라이언트가 자신의 내부 ID를 알 필요가 없다(08 §1.1, BD-14 식별자 은닉).
 *
 * <p><b>팔로워·팔로잉은 수치만 담는다.</b> 목록은 제공하지 않는다 — 명세가 정한 범위다.
 *
 * @param provider 인증 수단. 계정당 하나다 — 계정 연결 흐름이 없어 한 회원에 여러 공급자가 붙지 않는다
 * @param email 항상 있다. 이메일 없는 계정은 가입 단계에서 걸러진다(06 §2.2, S15P11A705-152)
 * @param recordCount 활성 Record 수
 * @param collectionCount 활성 Collection 수
 * @param followerCount 나를 팔로우하는 회원 수. 단위가 Collection이 아니라 <b>작성자</b>다 —
 *     유니크가 {@code (followee_member_id, follower_member_id)}라 한 사람이 여러 Collection을
 *     팔로우해도 1이다
 * @param followingCount 내가 팔로우하는 작성자 수
 */
public record MeSummaryResponse(
	SocialProvider provider,
	String email,
	long recordCount,
	long collectionCount,
	long followerCount,
	long followingCount
) {
}
