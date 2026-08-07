package com.pinlog.pinlogback.domain.member.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 나의 활동 기록 집계 응답(S15P11A705-397). 화면 진입 시 1회 호출된다.
 *
 * <p><b>{@code memberId}를 담지 않는다.</b> 개인 API는 서버가 쿠키로 사용자를 식별하므로
 * 클라이언트가 자신의 내부 ID를 알 필요가 없다(08 §1.1, BD-14 식별자 은닉).
 *
 * <p>기간은 <b>전체 누적</b>이다. 기간 파라미터를 두지 않는다 — 화면이 "지금까지"를 보여주는
 * 자리라 범위를 고를 여지가 없고, 파라미터를 열면 캐시·검증·문서가 함께 늘어난다.
 *
 * <p><b>날짜 경계는 전부 KST다.</b> {@code record.created_at}은 {@code TIMESTAMPTZ}라 UTC로
 * 끊으면 KST 자정 직후의 기록이 전날로 붙는다. 월·일 집계는 모두
 * {@code created_at AT TIME ZONE 'Asia/Seoul'} 위에서 계산한다.
 *
 * @param totals 화면 상단의 큰 숫자
 * @param months 첫 기록이 있는 달부터 이번 달까지. 기록이 없는 달도 0으로 채워 빈칸 없이 이어진다
 * @param areas 주소의 시·구 기준 상위 5곳
 * @param counts 기록에 딸린 것들의 개수
 * @param highlights 기록에서 그대로 뽑은 단일 사실들
 */
public record MeActivityResponse(
	Totals totals,
	List<MonthCount> months,
	List<AreaCount> areas,
	Counts counts,
	Highlights highlights
) {

	/**
	 * @param placeCount 기록한 장소 수. 활성 Record 수와 같다 — {@code uq_record_active}가
	 *     회원·장소당 활성 Record를 1개로 묶어 두기 때문이다(V3:43)
	 * @param districtCount 발자국이 닿은 자치구 수. 주소에서 시·구를 못 뽑는 행은 세지 않는다
	 * @param firstRecordedOn 첫 기록일(KST). 기록이 없으면 {@code null}
	 */
	public record Totals(long placeCount, long districtCount, LocalDate firstRecordedOn) {
	}

	/** @param month {@code YYYY-MM}(KST) */
	public record MonthCount(String month, long recordCount) {
	}

	/** @param district 주소에서 뽑은 시·구 이름 */
	public record AreaCount(String district, long recordCount) {
	}

	/**
	 * @param contextCount 활성 맥락 메모 수
	 * @param collectionCount 활성 컬렉션 수
	 * @param recordedMonthCount 기록이 <b>실제로 있는</b> 달의 수. {@code months}의 길이와 다르다 —
	 *     그쪽은 빈 달까지 채운 구간 길이다
	 */
	public record Counts(long contextCount, long collectionCount, long recordedMonthCount) {
	}

	/**
	 * @param firstPlaceName 처음 기록한 장소 이름. 기록이 없으면 {@code null}
	 * @param lastPlaceName 가장 최근에 기록한 장소 이름. 기록이 없으면 {@code null}
	 * @param busiestDay 하루에 가장 많이 기록한 날. 기록이 없으면 {@code null}
	 */
	public record Highlights(String firstPlaceName, String lastPlaceName, BusiestDay busiestDay) {
	}

	/** @param date 그 날짜(KST) */
	public record BusiestDay(LocalDate date, long recordCount) {
	}
}
