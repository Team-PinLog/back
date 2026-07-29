package com.pinlog.pinlogback.domain.follow.service;

import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.follow.dto.FollowResponse;
import com.pinlog.pinlogback.domain.follow.dto.FollowedCollectionResponse;
import com.pinlog.pinlogback.domain.follow.entity.Follow;
import com.pinlog.pinlogback.domain.follow.repository.FollowRepository;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.global.exception.DuplicateFollowException;
import com.pinlog.pinlogback.global.exception.InvalidRequestException;
import com.pinlog.pinlogback.global.exception.ResourceNotFoundException;
import com.pinlog.pinlogback.global.exception.SelfFollowNotAllowedException;
import com.pinlog.pinlogback.global.response.Cursor;
import com.pinlog.pinlogback.global.response.CursorPage;

/**
 * Shelf Follow 유스케이스(데이터모델 2.8, BD-15). Follow 대상은 User(Shelf)이며 진입점은
 * 공개 Collection의 id다(식별자 은닉, BD-14). Library는 전용 조회 없이
 * {@code GET /follows} + {@code GET /follows/{id}/collections} 조합으로 구성된다.
 */
@Service
public class FollowService {

	private static final int MAX_ALIAS_LENGTH = 20;

	/** 활성행 부분 유니크 인덱스(V3). 동일 Shelf 중복 Follow를 DB가 막는 지점이다. */
	private static final String UNIQUE_ACTIVE_FOLLOW = "uq_follow_active";

	private final FollowRepository followRepository;
	private final CollectionRepository collectionRepository;
	private final MemberRepository memberRepository;

	public FollowService(FollowRepository followRepository, CollectionRepository collectionRepository,
		MemberRepository memberRepository) {
		this.followRepository = followRepository;
		this.collectionRepository = collectionRepository;
		this.memberRepository = memberRepository;
	}

	/**
	 * Shelf Follow 생성(API 명세 8.2). 중복 확인과 저장 사이는 원자적이지 않아, 동시 요청 둘이
	 * 나란히 "중복 아님"으로 판정하고 {@code uq_follow_active}를 위반할 수 있다. 그래서 사전 확인과
	 * <b>제약 위반 처리를 둘 다 둔다</b> — 앞의 것은 흔한 순차 요청을 예외 없이 거르고, 뒤의 것은
	 * 경합에서 진 요청이 500이 아니라 409로 나가게 하는 안전망이다(S15P11A705-104).
	 *
	 * <p>Record 생성(S15P11A705-105)과 달리 {@code ON CONFLICT DO NOTHING}으로 흡수하지 않는다.
	 * 중복 팔로우는 기존 것을 돌려주면 되는 멱등 연산이 아니라 <b>거절해야 하는 요청</b>이기 때문이다.
	 * 위반 뒤 DB 작업을 이어가지 않고 예외만 바꿔 던지므로, 트랜잭션이 rollback-only가 되어도 문제가 없다.
	 */
	@Transactional
	public FollowResponse follow(Long memberId, Long collectionId) {
		Collection collection = collectionRepository.findById(collectionId)
			.filter(Collection::isPublished)
			.orElseThrow(ResourceNotFoundException::new);
		Long followeeMemberId = collection.getMemberId();
		if (!memberRepository.isActive(followeeMemberId)) {
			// 작성자가 탈퇴한 Collection은 공개 대상이 아니다 — 존재를 노출하지 않는다.
			throw new ResourceNotFoundException();
		}
		if (followeeMemberId.equals(memberId)) {
			throw new SelfFollowNotAllowedException();
		}
		followRepository.findByFolloweeMemberIdAndFollowerMemberId(followeeMemberId, memberId)
			.ifPresent(existing -> {
				throw new DuplicateFollowException();
			});
		try {
			Follow follow = followRepository.saveAndFlush(Follow.create(followeeMemberId, memberId));
			return FollowResponse.from(follow);
		} catch (DataIntegrityViolationException e) {
			throw duplicateFollowOr(e);
		}
	}

	/**
	 * 활성행 부분 유니크 위반만 409로 바꾸고 나머지는 그대로 올린다. 모든
	 * {@link DataIntegrityViolationException}을 409로 뭉뚱그리면 성격이 다른 위반(예: FK)이
	 * "이미 팔로우한 책장입니다"로 나가 원인을 가린다.
	 */
	private RuntimeException duplicateFollowOr(DataIntegrityViolationException cause) {
		if (cause.getCause() instanceof ConstraintViolationException violation
			&& UNIQUE_ACTIVE_FOLLOW.equalsIgnoreCase(violation.getConstraintName())) {
			return new DuplicateFollowException();
		}
		return cause;
	}

	@Transactional(readOnly = true)
	public CursorPage<FollowResponse> listMine(Long memberId, String cursor, Integer size) {
		int pageSize = CursorPage.normalizeSize(size);
		Pageable probe = PageRequest.of(0, pageSize + 1);
		List<Follow> rows;
		if (cursor == null || cursor.isBlank()) {
			rows = followRepository.findFirstPageByFollowerMemberId(memberId, probe);
		} else {
			Cursor decoded = Cursor.decode(cursor);
			rows = followRepository.findPageByFollowerMemberIdAfter(
				memberId, decoded.sortKeyAsInstant(), decoded.id(), probe);
		}
		boolean hasNext = rows.size() > pageSize;
		List<Follow> page = hasNext ? rows.subList(0, pageSize) : rows;
		List<FollowResponse> items = page.stream().map(FollowResponse::from).toList();
		if (!hasNext) {
			return CursorPage.last(items);
		}
		Follow last = page.get(page.size() - 1);
		return CursorPage.of(items, Cursor.encode(last.getCreatedAt(), last.getId()));
	}

	/**
	 * 팔로우한 Shelf의 공개 Collection 목록(API 명세 9.3). followee가 탈퇴했으면 목록에서
	 * 제외한다는 규칙에 따라 404가 아니라 빈 목록을 돌려준다.
	 */
	@Transactional(readOnly = true)
	public CursorPage<FollowedCollectionResponse> listFollowedCollections(Long memberId, Long followId,
		String cursor, Integer size) {
		Follow follow = ownedFollow(memberId, followId);
		if (!memberRepository.isActive(follow.getFolloweeMemberId())) {
			return CursorPage.empty();
		}
		int pageSize = CursorPage.normalizeSize(size);
		Pageable probe = PageRequest.of(0, pageSize + 1);
		List<Collection> rows;
		if (cursor == null || cursor.isBlank()) {
			rows = collectionRepository.findPublishedFirstPageByMemberId(
				follow.getFolloweeMemberId(), probe);
		} else {
			Cursor decoded = Cursor.decode(cursor);
			rows = collectionRepository.findPublishedPageByMemberIdAfter(
				follow.getFolloweeMemberId(), decoded.sortKeyAsInstant(), decoded.id(), probe);
		}
		boolean hasNext = rows.size() > pageSize;
		List<Collection> page = hasNext ? rows.subList(0, pageSize) : rows;
		List<FollowedCollectionResponse> items = page.stream()
			.map(FollowedCollectionResponse::from)
			.toList();
		if (!hasNext) {
			return CursorPage.last(items);
		}
		Collection last = page.get(page.size() - 1);
		return CursorPage.of(items, Cursor.encode(last.getCreatedAt(), last.getId()));
	}

	/**
	 * 별칭 수정·제거(API 명세 8.3). 앞뒤 공백 제거 후 빈 값은 null(제거)로 저장하고,
	 * 20자 초과는 거절한다. 동일 별칭 중복은 허용한다.
	 */
	@Transactional
	public FollowResponse changeAlias(Long memberId, Long followId, String alias) {
		Follow follow = ownedFollow(memberId, followId);
		follow.changeDisplayName(normalizeAlias(alias));
		return FollowResponse.from(follow);
	}

	@Transactional
	public void unfollow(Long memberId, Long followId) {
		Follow follow = ownedFollow(memberId, followId);
		follow.softDelete();
	}

	private Follow ownedFollow(Long memberId, Long followId) {
		Follow follow = followRepository.findById(followId).orElseThrow(ResourceNotFoundException::new);
		if (!follow.isOwnedBy(memberId)) {
			throw new ResourceNotFoundException();
		}
		return follow;
	}

	private String normalizeAlias(String alias) {
		if (alias == null) {
			return null;
		}
		String stripped = alias.strip();
		if (stripped.isEmpty()) {
			return null;
		}
		if (stripped.length() > MAX_ALIAS_LENGTH) {
			throw new InvalidRequestException("별칭은 공백 제거 후 20자 이하여야 합니다.");
		}
		return stripped;
	}
}
