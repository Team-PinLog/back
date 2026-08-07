package com.pinlog.pinlogback.domain.record.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.record.entity.Record;

/**
 * 최근 Record 목록의 카드 한 장(API 명세 5.9). 홈 화면 "최근 기록" 영역이 쓴다.
 *
 * <p><b>{@code contexts} 필드를 두지 않는다.</b> {@link RecordDetailResponse}를 재사용하면 이
 * 응답에서는 항상 비워 보내야 하는 자리가 생기고, 비워 보내는 자리는 언젠가 누가 채운다. 자리를
 * 없애 두면 실수가 유출이 아니라 컴파일 오류로 끝난다 — {@code PublicRecordCardResponse}가
 * {@code contexts}를 고정 {@code null}로 둔 것과 같은 이유(BD-13)이며, 여기서는 한 걸음 더 간다.
 *
 * <p>{@code keywordStatus}도 싣지 않는다(명세 5.9). 이 목록은 갓 만든 Record만 보여주므로 AI 판정
 * 전인 항목이 흔하지만, 화면이 "분석 중"과 "키워드 0건"을 구분할 필요가 생기면 그때 필드를 더한다 —
 * {@code keywords} 배열의 계약을 바꾸지 않으므로 하위 호환 변경이다.
 */
public record RecentRecordCardResponse(
	Long recordId,
	PlaceSummaryResponse place,
	List<String> keywords,
	Instant createdAt
) {

	/** @param keywords 소유자 범위(PUBLIC + PRIVATE_ONLY) 집계. 없으면 빈 목록이며 {@code null}이 아니다 */
	public static RecentRecordCardResponse of(Record record, Place place, List<String> keywords) {
		return new RecentRecordCardResponse(
			record.getId(),
			PlaceSummaryResponse.from(place),
			keywords,
			record.getCreatedAt()
		);
	}
}
