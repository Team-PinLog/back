package com.pinlog.pinlogback.domain.ai.client;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * {@code POST /internal/v1/context/process} 요청 본문. 논리 계약의 정본은 공용 계약
 * {@code static/05_AI_설계.md} 13.1이고, 실행 가능한 계약은 ai 레포의
 * {@code app/schema/context.py::ContextProcessRequest}다.
 *
 * <p>필드 이름을 camelCase로 두는 이유: FastAPI 스키마가 camelCase를 그대로 받는다. Jackson 기본
 * 전략과도 같아 별도 {@code @JsonProperty}가 필요 없다.
 *
 * <p><b>Context 본문 버전 필드는 보내지 않는다.</b> {@code contextId}가 곧 본문의 정체성이므로
 * FastAPI가 버전을 비교할 이유가 없다.
 *
 * @param contextId 불변 Context 본문의 식별자
 * @param userId 검색 범위 필터값. 인증값이 아니며 FastAPI는 검증하지 않는다
 * @param recordId 소속 Record
 * @param text {@code contextId}의 Core 본문 그대로. 다른 곳에서 넘어온 문자열을 신뢰하지 않는다
 * @param placeMeta 장소 metadata. MVP FastAPI는 받아 두기만 하고 임베딩 입력에 결합하지 않는다
 */
public record ContextProcessRequest(
	Long contextId,
	Long userId,
	Long recordId,
	String text,
	@Nullable PlaceMeta placeMeta
) {

	/**
	 * 임베딩 입력 결합 여부는 AI 파트가 정한다(모델 프로파일 변경 대상). 백엔드는 Place 마스터가
	 * 가진 값을 그대로 실어 보낼 뿐 해석하지 않는다.
	 */
	public record PlaceMeta(
		Long placeId,
		String name,
		@Nullable String address,
		@Nullable String roadAddress,
		BigDecimal lat,
		BigDecimal lng
	) {
	}
}
