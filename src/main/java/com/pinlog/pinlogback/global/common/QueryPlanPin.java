package com.pinlog.pinlogback.global.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 선택도가 파라미터 값에 좌우되는 쿼리를 <b>custom plan에 묶는다</b>(S15P11A705-404).
 *
 * <p><b>왜 필요한가.</b> pgjdbc는 같은 문장을 5회 실행한 뒤 서버 프리페어로 전환하고, 그 시점에
 * Postgres가 generic plan을 고를 수 있다. generic plan은 파라미터 값을 모르므로 선택도를 추정에
 * 맡기는데, bbox처럼 값에 따라 걸러지는 양이 크게 달라지는 조건에서는 그 추정이 조인 순서를
 * 뒤집는다. 지도 bbox 조회 실측(record 1,012만)에서 마커 조회가 2.8ms에서 45.4~61.6ms로,
 * 키워드 조회가 5.4ms에서 32~34ms로 벌어졌다.
 *
 * <p><b>SQL을 두 갈래로 나누는 것으로는 막지 못한다.</b> {@code (:swLat IS NULL OR ...)}로 합치지
 * 않고 bbox 있는 문장과 없는 문장을 따로 두면 <em>플래너가 bbox 조건의 존재를 안다</em>는 이득은
 * 얻지만(S15P11A705-388), 값 자체는 여전히 파라미터다. generic plan은 그 값을 못 읽는다.
 *
 * <p><b>CTE로 감싸는 것도 답이 아니다.</b> 실측에서 bbox를 포함한 {@code MATERIALIZED} CTE는 전혀
 * 나아지지 않았고(33ms), bbox를 뺀 CTE로 회원 record를 먼저 고정해도 절반만 회복했다(19ms —
 * 플래너가 bbox 안 place를 해시로 쌓는다). 직관과 반대이므로 적어 둔다.
 *
 * <p><b>전역으로 걸지 않는 이유.</b> {@code prepareThreshold=0}이나 데이터소스 초기화 SQL로 한 번에
 * 끄면 다른 쿼리가 계획을 재사용해 얻는 이득까지 함께 버린다. 어디까지 걸어야 하는지는
 * S15P11A705-405의 전수 감사가 판단하며, 그때까지는 실측으로 확인된 자리에만 건다.
 */
@Component
public class QueryPlanPin {

	private final JdbcTemplate jdbc;

	public QueryPlanPin(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 이 트랜잭션이 끝날 때까지 계획 캐시를 끈다.
	 *
	 * <p><b>반드시 트랜잭션 안에서 불러야 한다.</b> {@code SET LOCAL}은 트랜잭션 밖에서는 경고만
	 * 남기고 아무 일도 하지 않는다 — 즉 호출부의 {@code @Transactional}이 사라지면 이 보호가
	 * <b>조용히</b> 없어진다. {@code MapBboxPlanCacheTests}가 그 상태를 잡는다.
	 *
	 * <p>범위를 트랜잭션으로 잡는 것이 요점이다. 커넥션 수준으로 걸면 그 커넥션을 물려받는 다음
	 * 요청의 계획까지 바꿔 놓는다.
	 */
	public void forceCustomPlanForThisTransaction() {
		jdbc.execute("SET LOCAL plan_cache_mode = force_custom_plan");
	}
}
