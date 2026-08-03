package com.pinlog.pinlogback.domain.collection.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 여러 회원의 발행 Collection <b>첫 페이지</b>를 한 번의 질의로 모은다(API 명세 9.2,
 * S15P11A705-244). 팔로우 목록에 책장별 Collection을 실을 때 책장 수만큼 반복 조회하면
 * 그대로 N+1이라, 창 함수로 회원별 상위 {@code probeSize}행만 끊어 온다.
 *
 * <p>정렬({@code created_at DESC, id DESC})과 노출 조건({@code is_published}·소프트 삭제 제외)은
 * {@code CollectionRepository.findPublishedFirstPageByMemberId}(9.3)와 같아야 한다 — 여기서 낸
 * 커서를 그 엔드포인트가 그대로 이어받는 계약이기 때문이다. 작성자 탈퇴 필터는 두지 않는다.
 * 호출부의 팔로우 목록 질의가 탈퇴 회원을 이미 걸렀고, 여기 넘어오는 것은 그 결과다.
 *
 * <p>엔티티가 아니라 행 record를 돌려주는 이유: 창 함수는 JPQL에 없어 native가 필요한데,
 * native로 엔티티를 채우면 {@code @SQLRestriction} 같은 매핑 규칙 밖에서 엔티티가 만들어져
 * 영속성 컨텍스트와 어긋날 수 있다. 응답 조립에 필요한 네 컬럼만 가져온다.
 */
@Repository
public class CollectionFirstPageRepository {

	private static final String PUBLISHED_FIRST_PAGES_SQL = """
		SELECT t.member_id, t.id, t.title, t.record_count, t.created_at
		FROM (
			SELECT c.member_id, c.id, c.title, c.record_count, c.created_at,
				row_number() OVER (PARTITION BY c.member_id ORDER BY c.created_at DESC, c.id DESC) AS rn
			FROM core.collection c
			WHERE c.member_id IN (:memberIds)
				AND c.is_published = true
				AND c.deleted_at IS NULL
		) t
		WHERE t.rn <= :probeSize
		ORDER BY t.member_id, t.created_at DESC, t.id DESC
		""";

	/** 9.3 항목 조립에 필요한 컬럼만 담는 행. {@code keywords}는 호출부가 별도 집계로 붙인다. */
	public record PublishedCollectionRow(Long collectionId, String title, int recordCount, Instant createdAt) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	public CollectionFirstPageRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 회원별 발행 Collection을 최신순으로 {@code probeSize}행까지 모은다. 호출부는
	 * {@code pageSize + 1}을 넘겨 초과 행 유무로 회원별 {@code hasNext}를 판정한다.
	 *
	 * @return 회원 id → 그 회원의 행 목록(최신순). <b>발행 Collection이 없는 회원은 키가 없다</b>
	 *     — 호출부가 빈 목록으로 채운다
	 */
	public Map<Long, List<PublishedCollectionRow>> findPublishedFirstPages(List<Long> memberIds, int probeSize) {
		if (memberIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<PublishedCollectionRow>> byMember = new LinkedHashMap<>();
		jdbc.query(PUBLISHED_FIRST_PAGES_SQL, Map.of("memberIds", memberIds, "probeSize", probeSize), rows -> {
			byMember.computeIfAbsent(rows.getLong("member_id"), key -> new ArrayList<>())
				.add(new PublishedCollectionRow(
					rows.getLong("id"),
					rows.getString("title"),
					rows.getInt("record_count"),
					rows.getObject("created_at", OffsetDateTime.class).toInstant()));
		});
		return byMember;
	}
}
