package com.pinlog.pinlogback.domain.feed.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.feed.repository.FeedCollectionCard;

/**
 * Feed 목록의 항목 하나(API 명세 10.1).
 *
 * <p><b>소유자 식별 정보를 담는 필드가 없다.</b> 조건부 직렬화나 상속으로 감추지 않고 자리를
 * 없앤다 — 실수가 유출이 아니라 컴파일 오류로 실패해야 한다(BD-13·BD-14). Context 원문도 같다.
 *
 * @param position 응답 목록에서의 0-based 순서. 클라이언트가 CLICK 이벤트에 그대로 돌려보낸다
 * @param collectionId 대상 Collection
 * @param title Collection 제목
 * @param recordCount 담긴 Record 수
 * @param keywords 공개 가능한 {@code PUBLIC} Keyword의 {@code keyword_preset.display_name}이다.
 *                 <b>{@code code}는 내부 식별용이라 노출하지 않는다</b>(08 §6.1 — 모든 Keyword 응답
 *                 공통). 점수 계산은 {@code code}로 하고 여기서만 표시값으로 옮긴다
 *                 (S15P11A705-252). <b>최대 {@link #KEYWORD_LIMIT}개</b>이며 선정·정렬 규칙은
 *                 feed-recommendation 3.7.1이다. AI 처리가 끝나지 않았으면 빈 배열이며
 *                 <b>오류가 아니다</b>(feed-recommendation 3.7)
 * @param createdAt Collection 생성 시각
 */
public record FeedCollectionItemResponse(
	int position,
	Long collectionId,
	String title,
	int recordCount,
	List<String> keywords,
	String coverImageUrl,
	Instant createdAt
) {

	/**
	 * 한 카드에 실을 Keyword 상한(S15P11A705-278, P46).
	 *
	 * <p><b>프론트 카드 레이아웃이 요구한 값이다</b>(구두 합의, 2026-08-03). 데이터에서 나온 수가
	 * 아니므로 분포가 바뀌어도 재계산하지 않는다 — 바뀔 때는 화면이 먼저 바뀐다. 같은 날 4에서
	 * 3으로 한 번 바뀌었고, 그때도 움직인 것은 화면 쪽이지 측정값이 아니었다.
	 *
	 * <p><b>설정값이 아니라 상수인 이유</b>도 같다. 프론트가 이 수로 레이아웃을 확정하므로 배포마다
	 * 달라지면 계약이 아니다. {@code feed-scoring.md}의 튜닝값들과는 층이 다르다 — 그쪽은 응답
	 * 계약에 드러나지 않는 내부 랭킹 거동이라 재배포 없이 움직여도 된다.
	 */
	public static final int KEYWORD_LIMIT = 3;

	public FeedCollectionItemResponse {
		keywords = List.copyOf(keywords);
	}

	public static FeedCollectionItemResponse of(int position, FeedCollectionCard card,
		List<String> keywords) {
		return new FeedCollectionItemResponse(
			position, card.collectionId(), card.title(), card.recordCount(), keywords,
			card.coverImageUrl(), card.createdAt());
	}
}
