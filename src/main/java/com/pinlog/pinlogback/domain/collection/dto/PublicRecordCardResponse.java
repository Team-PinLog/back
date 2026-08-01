package com.pinlog.pinlogback.domain.collection.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.record.dto.PlaceSummaryResponse;
import com.pinlog.pinlogback.domain.record.entity.Record;

/**
 * 타인 공개 조회의 Record 카드(API 명세 7.3 타인 응답, 데이터모델 5.2 DTO 분리).
 *
 * <p>contexts는 생성자로 받지 않는 <b>고정 null</b>이다 — 명세의 wire 계약({@code "contexts": null})을
 * 지키면서, Context 본문을 담을 자리 자체를 없애 실수가 유출이 아니라 컴파일 오류로 실패하게
 * 만든다(BD-13). 소유자용 {@code RecordDetailResponse}와 상속 관계를 두지 않는다.
 */
public record PublicRecordCardResponse(
	Long recordId,
	PlaceSummaryResponse place,
	List<String> contexts,
	List<String> keywords,
	Instant createdAt,
	Instant addedToCollectionAt
) {

	/** @param keywords 타인 범위({@code PUBLIC}만) Keyword — 호출부가 공개용 집계로 채운다 */
	public static PublicRecordCardResponse of(Record record, Place place, List<String> keywords,
		Instant addedToCollectionAt) {
		return new PublicRecordCardResponse(
			record.getId(),
			PlaceSummaryResponse.from(place),
			null,
			keywords,
			record.getCreatedAt(),
			addedToCollectionAt
		);
	}
}
