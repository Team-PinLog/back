package com.pinlog.pinlogback.domain.member.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pinlog.pinlogback.domain.ai.repository.AiDerivedDataRepository;
import com.pinlog.pinlogback.domain.auth.service.RefreshTokenStore;
import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.entity.CollectionRecord;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRecordRepository;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.follow.entity.Follow;
import com.pinlog.pinlogback.domain.follow.repository.FollowRepository;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.global.exception.UnauthorizedException;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 탈퇴(API 명세 3.6, 데이터모델 6.9).
 *
 * <p><b>순서가 계약이므로 한 메서드에 담는다.</b> 도메인별 서비스로 흩뜨리면 6.9가 정한 흐름을
 * 한 곳에서 읽을 수 없고, 각 단계가 같은 트랜잭션에 있다는 보장도 호출부마다 따라가 봐야 알게 된다.
 * 다른 도메인의 리포지토리를 직접 주입하는 것은 {@code RecordDeletionService}에 이미 선례가 있다.
 *
 * <p>되돌릴 수 없다. 모든 삭제는 소프트 삭제이지만 {@code social_account}의 개인정보는 마스킹으로
 * 파기되므로 복구 경로가 없다(06 §2.2).
 */
@Slf4j
@Service
public class MemberWithdrawalService {

	private final MemberRepository memberRepository;
	private final SocialAccountRepository socialAccountRepository;
	private final RecordRepository recordRepository;
	private final ContextRepository contextRepository;
	private final CollectionRepository collectionRepository;
	private final CollectionRecordRepository collectionRecordRepository;
	private final FollowRepository followRepository;
	private final AiDerivedDataRepository aiDerivedDataRepository;
	private final RefreshTokenStore refreshTokenStore;

	public MemberWithdrawalService(
		MemberRepository memberRepository,
		SocialAccountRepository socialAccountRepository,
		RecordRepository recordRepository,
		ContextRepository contextRepository,
		CollectionRepository collectionRepository,
		CollectionRecordRepository collectionRecordRepository,
		FollowRepository followRepository,
		AiDerivedDataRepository aiDerivedDataRepository,
		RefreshTokenStore refreshTokenStore
	) {
		this.memberRepository = memberRepository;
		this.socialAccountRepository = socialAccountRepository;
		this.recordRepository = recordRepository;
		this.contextRepository = contextRepository;
		this.collectionRepository = collectionRepository;
		this.collectionRecordRepository = collectionRecordRepository;
		this.followRepository = followRepository;
		this.aiDerivedDataRepository = aiDerivedDataRepository;
		this.refreshTokenStore = refreshTokenStore;
	}

	/**
	 * 데이터모델 6.9의 흐름을 그대로 수행한다.
	 *
	 * <p><b>행 잠금을 쓰지 않는다.</b> {@code RecordDeletionService.cascadeDelete}가 Record·Collection을
	 * 잠그는 이유는 "활성 Context 1개 이상" 불변식과 {@code record_count} 산술이 동시 요청과 경합하기
	 * 때문이다. 탈퇴는 그 회원의 데이터를 전부 지우므로 개수를 세어 분기할 일이 없고, Collection은
	 * 소유자 자기 Record만 담아 타 회원과 경합하지도 않는다.
	 *
	 * <p><b>{@code record_count}를 갱신하지 않는다.</b> Collection 자체가 함께 죽는다
	 * ({@code CollectionService.deleteCollection}이 같은 이유로 갱신하지 않는다).
	 *
	 * <p><b>Refresh 폐기는 커밋 이후에 한다.</b> Redis는 DB 트랜잭션에 참여할 수 없으므로 트랜잭션
	 * 안에 두면 "폐기는 됐는데 탈퇴는 롤백된" 상태를 <b>없앨 수 없다</b> — 폐기가 반환한 뒤 커밋이
	 * 실패하거나, 폐기가 키를 일부 지우고 던지면 그대로 남는다. 마지막에 두는 것으로는 창이 좁아질
	 * 뿐이다.
	 *
	 * <p>그래서 커밋 이후로 옮기고 <b>실패를 삼킨다</b>. 이 방향의 잔여 위험은 무해하다 — 폐기가
	 * 실패해 Refresh가 살아남아도, 그것으로 받는 Access는 {@code JwtAuthenticationFilter}의 탈퇴
	 * 판정에 막힌다(BD-41). 반대로 삼키지 않으면 이미 커밋된 탈퇴가 500으로 응답해 쿠키도 지워지지
	 * 않는다.
	 *
	 * @throws UnauthorizedException 이미 탈퇴한 회원일 때. 인증 계층이 먼저 막지만 그 보증이
	 *     필터 설정에 있고 이 메서드의 타입에는 없어 한 번 더 확인한다
	 */
	@Transactional
	public void withdraw(Long memberId) {
		Member member = memberRepository.findById(memberId).orElseThrow(UnauthorizedException::new);

		member.softDelete();
		socialAccountRepository.findByMemberId(memberId).forEach(SocialAccount::withdraw);

		List<Record> records = recordRepository.findByMemberId(memberId);
		invalidateContextsOf(records);
		records.forEach(Record::softDelete);

		List<Collection> collections = collectionRepository.findByMemberId(memberId);
		List<Long> collectionIds = collections.stream().map(Collection::getId).toList();
		if (!collectionIds.isEmpty()) {
			collectionRecordRepository.findByCollectionIdIn(collectionIds)
				.forEach(CollectionRecord::softDelete);
		}
		collections.forEach(Collection::softDelete);

		// 양방향이다 — 내가 만든 팔로우와 나를 대상으로 하는 팔로우 모두 지운다(6.9).
		followRepository.findAllInvolving(memberId).forEach(Follow::softDelete);

		revokeEverySessionAfterCommit(memberId);
	}

	/** 커밋 이후에 최선 노력으로 폐기한다. 이유는 {@link #withdraw(Long)} javadoc에 있다. */
	private void revokeEverySessionAfterCommit(Long memberId) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					refreshTokenStore.revokeAll(memberId);
				} catch (RuntimeException e) {
					// 탈퇴는 이미 확정됐다. 여기서 던지면 확정된 탈퇴가 500으로 응답하고 쿠키도
					// 지워지지 않는다. 남은 Refresh는 BD-41의 탈퇴 판정이 무력화한다.
					log.error("failed to revoke refresh tokens after withdrawal: memberId={}", memberId, e);
				}
			}
		});
	}

	/**
	 * AI 파생 데이터를 무효화한다. Context 소프트 삭제와 <b>같은 트랜잭션</b>이어야 한다 — 나누면
	 * "core는 지웠는데 검색 결과에는 계속 나오는" 부분 실패가 남는다(BD-37).
	 *
	 * <p>영향 행이 0이어도 정상이다. 파생 데이터가 생기기 전에 삭제된 경우이고, Record가 없는 회원은
	 * 빈 목록이라 호출 자체가 no-op이다.
	 */
	private void invalidateContextsOf(List<Record> records) {
		if (records.isEmpty()) {
			return;
		}
		List<Long> recordIds = records.stream().map(Record::getId).toList();
		List<Context> contexts =
			contextRepository.findByRecordIdInOrderByOriginCreatedAtAscIdAsc(recordIds);
		contexts.forEach(Context::softDelete);
		aiDerivedDataRepository.invalidate(contexts.stream().map(Context::getId).toList());
	}
}
