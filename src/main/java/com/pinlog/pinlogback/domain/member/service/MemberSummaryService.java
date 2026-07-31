package com.pinlog.pinlogback.domain.member.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.follow.repository.FollowRepository;
import com.pinlog.pinlogback.domain.member.dto.MeSummaryResponse;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;

/**
 * 마이페이지 요약 조회(API 명세 3.5). 진입 시 1회 호출되는 집계다.
 *
 * <p>네 카운트를 각각 세는 쿼리 넷을 낸다. 한 번의 조인으로 묶지 않는 이유는 <b>서로 다른
 * 테이블의 독립 집계</b>라 조인이 카운트를 곱하기 때문이다 — Record 3건과 Collection 2건을
 * 조인하면 6이 된다. {@code count(DISTINCT ...)}로 피할 수는 있지만, 화면 진입당 1회 호출되는
 * 경로에서 PK·FK 인덱스를 타는 count 넷은 그 복잡도를 살 이유가 되지 않는다.
 */
@Service
public class MemberSummaryService {

	private final SocialAccountRepository socialAccountRepository;
	private final RecordRepository recordRepository;
	private final CollectionRepository collectionRepository;
	private final FollowRepository followRepository;

	public MemberSummaryService(
		SocialAccountRepository socialAccountRepository,
		RecordRepository recordRepository,
		CollectionRepository collectionRepository,
		FollowRepository followRepository
	) {
		this.socialAccountRepository = socialAccountRepository;
		this.recordRepository = recordRepository;
		this.collectionRepository = collectionRepository;
		this.followRepository = followRepository;
	}

	/**
	 * @throws IllegalStateException 활성 회원에게 소셜 계정이 없을 때. 가입이 회원과 소셜 계정을
	 *     한 트랜잭션에서 만들므로(SocialLoginService) 정상 상태에서는 발생하지 않는다. 조용히
	 *     빈 값을 내보내면 "email은 항상 있다"는 계약(3.5)이 깨진 채 프론트로 나간다
	 */
	@Transactional(readOnly = true)
	public MeSummaryResponse summarize(Long memberId) {
		SocialAccount account = socialAccountRepository.findByMemberId(memberId).stream()
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
				"활성 회원에게 소셜 계정이 없다: memberId=" + memberId));

		return new MeSummaryResponse(
			account.getProvider(),
			account.getEmail(),
			recordRepository.countByMemberId(memberId),
			collectionRepository.countByMemberId(memberId),
			followRepository.countByFolloweeMemberId(memberId),
			followRepository.countByFollowerMemberId(memberId));
	}
}
