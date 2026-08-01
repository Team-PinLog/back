package com.pinlog.pinlogback.domain.follow.service;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.ai.repository.ContextKeywordRepository;
import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.follow.dto.FollowedCollectionResponse;
import com.pinlog.pinlogback.domain.follow.dto.ShelfFollowState;
import com.pinlog.pinlogback.domain.follow.dto.ShelfResponse;
import com.pinlog.pinlogback.domain.follow.repository.FollowRepository;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.global.exception.ResourceNotFoundException;
import com.pinlog.pinlogback.global.response.Cursor;
import com.pinlog.pinlogback.global.response.CursorPage;

/**
 * 작성자 공개 책장 탐색(API 명세 8.1).
 *
 * <p><b>Feed 도메인이 아니라 여기에 있다.</b> 추천 점수·Profile·{@code feed_event} 어느 것도
 * 타지 않고, §9.3 {@code GET /follows/{followId}/collections}와 같은 데이터를 다른 진입 키로
 * 읽는다. 다른 것은 진입 키뿐이다 — 팔로우 전이라 {@code followId}가 없고 가진 것은 발견한
 * Collection의 id다(06 §5.3 식별자 은닉). Feed에서 진입하는 것은 화면 흐름이고 조회 대상은
 * Collection이다.
 *
 * <p>그래서 질의를 새로 만들지 않고 {@code CollectionRepository}의 발행 Collection 조회를
 * 그대로 쓴다. 같은 데이터에 두 번째 질의가 생기면 공개 범위 규칙을 지켜야 할 곳이 하나 늘어난다.
 */
@Service
public class ShelfService {

	private final CollectionRepository collectionRepository;
	private final FollowRepository followRepository;
	private final MemberRepository memberRepository;
	private final ContextKeywordRepository contextKeywordRepository;

	public ShelfService(CollectionRepository collectionRepository, FollowRepository followRepository,
		MemberRepository memberRepository, ContextKeywordRepository contextKeywordRepository) {
		this.collectionRepository = collectionRepository;
		this.followRepository = followRepository;
		this.memberRepository = memberRepository;
		this.contextKeywordRepository = contextKeywordRepository;
	}

	/**
	 * 진입점 Collection의 작성자가 가진 발행 Collection 한 페이지와 요청자 기준 팔로우 상태.
	 *
	 * <p>진입점이 공개 대상이 아닐 때 404로 은닉하는 판정이 {@code FollowService.follow}(8.2)와
	 * 같다. 같아야 하는 이유는 두 Endpoint가 <b>같은 진입점을 공유</b>하기 때문이다 — 한쪽에서만
	 * 존재가 드러나면 그쪽이 유출 지점이 된다.
	 *
	 * @param viewerId 요청자. 팔로우 상태 판정에만 쓴다
	 * @param collectionId 공개 진입점
	 */
	@Transactional(readOnly = true)
	public ShelfResponse browse(Long viewerId, Long collectionId, String cursor, Integer size) {
		Collection entry = collectionRepository.findById(collectionId)
			.filter(Collection::isPublished)
			.orElseThrow(ResourceNotFoundException::new);
		Long authorId = entry.getMemberId();
		if (!memberRepository.isActive(authorId)) {
			// 탈퇴한 작성자의 Collection은 공개 대상이 아니다 — 존재를 노출하지 않는다.
			throw new ResourceNotFoundException();
		}
		ShelfFollowState follow = followRepository
			.findByFolloweeMemberIdAndFollowerMemberId(authorId, viewerId)
			.map(ShelfFollowState::from)
			.orElseGet(ShelfFollowState::notFollowed);
		return new ShelfResponse(collectionId, follow, page(authorId, cursor, size));
	}

	/**
	 * 발행 Collection 커서 페이지. {@code size}는 공용 계약대로 보정하며 범위 밖 값을 400으로
	 * 거절하지 않는다 — "서버 방어 상한의 답은 하나"라는 S15P11A705-117 규약이다.
	 */
	private CursorPage<FollowedCollectionResponse> page(Long authorId, String cursor, Integer size) {
		int pageSize = CursorPage.normalizeSize(size);
		Pageable probe = PageRequest.of(0, pageSize + 1);
		List<Collection> rows;
		if (cursor == null || cursor.isBlank()) {
			rows = collectionRepository.findPublishedFirstPageByMemberId(authorId, probe);
		} else {
			Cursor decoded = Cursor.decode(cursor);
			rows = collectionRepository.findPublishedPageByMemberIdAfter(
				authorId, decoded.sortKeyAsInstant(), decoded.id(), probe);
		}
		boolean hasNext = rows.size() > pageSize;
		List<Collection> page = hasNext ? rows.subList(0, pageSize) : rows;
		List<FollowedCollectionResponse> items = withPublicKeywords(page);
		if (!hasNext) {
			return CursorPage.last(items);
		}
		Collection last = page.get(page.size() - 1);
		return CursorPage.of(items, Cursor.encode(last.getCreatedAt(), last.getId()));
	}

	/** 페이지 전체의 {@code PUBLIC} Keyword를 한 번에 집계해 항목에 붙인다(BD-18, S15P11A705-240). */
	private List<FollowedCollectionResponse> withPublicKeywords(List<Collection> page) {
		Map<Long, List<String>> keywords = contextKeywordRepository.findCollectionKeywordsPublic(
			page.stream().map(Collection::getId).toList());
		return page.stream()
			.map(collection -> FollowedCollectionResponse.from(
				collection, keywords.getOrDefault(collection.getId(), List.of())))
			.toList();
	}
}
