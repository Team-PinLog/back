package com.pinlog.pinlogback.domain.member.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.member.dto.MeActivityResponse;
import com.pinlog.pinlogback.domain.member.dto.MeActivityResponse.Counts;
import com.pinlog.pinlogback.domain.member.dto.MeActivityResponse.Highlights;
import com.pinlog.pinlogback.domain.member.dto.MeActivityResponse.MonthCount;
import com.pinlog.pinlogback.domain.member.repository.MemberActivityRepository;
import com.pinlog.pinlogback.domain.member.repository.MemberActivityRepository.FirstLastPlace;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;

/**
 * 나의 활동 기록 집계(S15P11A705-397). 화면 진입 시 1회 호출된다.
 *
 * <p>집계를 한 쿼리로 묶지 않는다. 서로 다른 테이블의 독립 집계라 조인이 카운트를 곱하기
 * 때문이며, 이는 {@link MemberSummaryService}가 같은 이유로 이미 택한 방식이다. 진입당 1회
 * 호출되는 경로에서 인덱스를 타는 집계 여섯은 그 복잡도를 살 이유가 되지 않는다.
 *
 * <p><b>빈 달 채우기를 DB가 아니라 여기서 한다.</b> "이번 달"이 구간의 끝인데, 그것을 SQL에서
 * 정하면 DB 서버의 시계와 타임존이 응답을 좌우한다. 애플리케이션이 KST로 정해서 채우면
 * 기준이 한 곳에 남는다.
 */
@Service
public class MemberActivityService {

	/** 날짜·월 경계의 기준. 한국 서비스이므로 사용자의 하루는 KST의 하루다. */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MemberActivityRepository activityRepository;
	private final ContextRepository contextRepository;
	private final CollectionRepository collectionRepository;

	public MemberActivityService(
		MemberActivityRepository activityRepository,
		ContextRepository contextRepository,
		CollectionRepository collectionRepository
	) {
		this.activityRepository = activityRepository;
		this.contextRepository = contextRepository;
		this.collectionRepository = collectionRepository;
	}

	@Transactional(readOnly = true)
	public MeActivityResponse summarize(Long memberId) {
		MemberActivityRepository.Totals totals = activityRepository.findTotals(memberId);
		FirstLastPlace places = activityRepository.findFirstAndLastPlace(memberId);

		return new MeActivityResponse(
			new MeActivityResponse.Totals(
				totals.placeCount(), totals.districtCount(), totals.firstRecordedOn()),
			fillGaps(activityRepository.findRecordedMonths(memberId), totals.firstRecordedOn()),
			activityRepository.findTopAreas(memberId),
			new Counts(
				contextRepository.countByMemberId(memberId),
				collectionRepository.countByMemberId(memberId),
				totals.recordedMonthCount()),
			new Highlights(
				places.firstPlaceName(),
				places.lastPlaceName(),
				activityRepository.findBusiestDay(memberId).orElse(null)));
	}

	/**
	 * 첫 기록이 있는 달부터 이번 달까지 빠짐없이 잇고, 기록이 없는 달을 0으로 채운다.
	 *
	 * <p>막대그래프가 x축을 건너뛰면 "그 달에 안 다녔다"가 아니라 "그 달이 없다"로 읽힌다 —
	 * 3월과 5월만 있는 그래프에서 4월이 사라지면 두 막대가 붙어 보인다.
	 *
	 * @param firstRecordedOn {@code null}이면 기록이 하나도 없다는 뜻이라 빈 목록을 준다
	 */
	private List<MonthCount> fillGaps(List<MonthCount> recorded, LocalDate firstRecordedOn) {
		if (firstRecordedOn == null) {
			return List.of();
		}
		Map<String, Long> byMonth = new LinkedHashMap<>();
		recorded.forEach(month -> byMonth.put(month.month(), month.recordCount()));

		List<MonthCount> filled = new ArrayList<>();
		YearMonth last = YearMonth.now(KST);
		for (YearMonth cursor = YearMonth.from(firstRecordedOn);
			!cursor.isAfter(last);
			cursor = cursor.plusMonths(1)) {
			String key = cursor.toString();
			filled.add(new MonthCount(key, byMonth.getOrDefault(key, 0L)));
		}
		return filled;
	}
}
