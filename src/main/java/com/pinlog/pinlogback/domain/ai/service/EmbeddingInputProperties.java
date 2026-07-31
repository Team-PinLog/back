package com.pinlog.pinlogback.domain.ai.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code POST /internal/v1/context/process}의 {@code text}를 어떻게 구성할지 정하는 스위치.
 *
 * <p><b>측정을 위해 존재하며 기본값은 현행 동작이다.</b> 2026-07-30 실사용자 데이터 E2E
 * ({@code S15P11A705-174})에서 질의 "밥 먹고 산책하면서 쉬어가는 공원"이 기대한 Record를 2위로
 * 밀어냈는데, 원인이 <b>질의의 「공원」이 본문에 없고 장소명에만 있다</b>는 것이었다. 임베딩 입력이
 * {@code contextBody} 하나이므로 장소명은 벡터에 들어갈 경로가 없다.
 *
 * <p>개선 축이 둘(입력 구성 · 임베딩 모델)이고 어느 쪽이 듣는지 모르는 상태라, 둘을 교차시킨
 * 4조건을 실경로로 재는 것이 이 스위치의 용도다. <b>차원·모델 채택이 확정되지 않았으므로 이 값을
 * 켠 상태로 배포하지 않는다</b> — 기본값 {@code false}가 측정 조건 A(현행 기준선)를 그대로
 * 재현하는 것이 이 클래스가 지켜야 할 성질이고, {@link EmbeddingInputComposerTest}가 그것을 붙든다.
 *
 * <p>{@code pinlog.ai} 아래에 있지만 {@link com.pinlog.pinlogback.domain.ai.AiProperties}에 합치지
 * 않는 기준은 {@link AiRescanProperties}와 같다 — 저쪽은 <b>FastAPI 연결 계약</b>(주소·시크릿·
 * 타임아웃)이고 이쪽은 <b>임베딩 입력 정책</b>이라 바뀌는 이유가 다르다.
 *
 * @param includePlaceName {@code true}면 {@code text}를 {@code "장소명. 본문"}으로 구성한다.
 *     구분자와 순서는 고정이다 — 이 스위치가 가리는 것은 "장소명을 넣는가 마는가"이지 최적 포맷이
 *     아니다. 켜면 <b>저장과 질의의 입력 형식이 비대칭</b>이 된다(사용자는 어느 장소를 찾는지 모르므로
 *     질의에는 장소명을 붙일 수 없다). 그 비대칭의 영향까지 포함해 재는 것이 측정의 목적이다
 */
@ConfigurationProperties("pinlog.ai.embedding-input")
public record EmbeddingInputProperties(
	boolean includePlaceName
) {
}
