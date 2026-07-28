package com.pinlog.pinlogback.domain.follow.service;

import java.util.List;

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

	private final FollowRepository followRepository;
	private final CollectionRepository collectionRepository;
	private final MemberRepository memberRepository;

	public FollowService(FollowRepository followRepository, CollectionRepository collectionRepository,
		MemberRepository memberRepository) {
		this.followRepository = followRepository;
		this.collectionRepository = collectionRepository;
		this.memberRepository = memberRepository;
	}

	@Transactional
	public FollowResponse follow(Long memberId, Long collectionId) {
		Collection collection = collectionRepository.findById(collectionId)
			.filter(Collection::isPublished)
			.orElseThrow(ResourceNotFoundException::new);
		Long followeeMemberId = collection.getMemberId();
		if (memberRepository.findById(followeeMemberId).isEmpty()) {
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
		Follow follow = followRepository.save(Follow.create(followeeMemberId, memberId));
		return FollowResponse.from(follow);
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
		if (memberRepository.findById(follow.getFolloweeMemberId()).isEmpty()) {
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
