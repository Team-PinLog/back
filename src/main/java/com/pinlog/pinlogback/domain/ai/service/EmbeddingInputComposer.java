package com.pinlog.pinlogback.domain.ai.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code process} 요청의 {@code text}를 구성한다. {@link EmbeddingInputProperties}가 형태를 정하고
 * 이 클래스가 그 형태를 만든다.
 *
 * <p><b>결합을 back에서 하는 이유.</b> FastAPI는 받은 {@code text}를 그대로 임베딩하므로 어느 쪽에서
 * 붙여도 벡터는 같다. 그런데도 여기서 붙이는 것은 <b>채택 시 실제로 고쳐야 할 곳이 여기</b>이기
 * 때문이다. 시딩 데이터의 {@code contextBody}에 장소명을 미리 박아 재면 화면에 보이는 본문이 오염되고,
 * 무엇보다 측정이 검증하는 코드 경로가 채택 후 배포할 코드 경로와 달라진다.
 *
 * <p>{@code placeMeta.name}은 이 결합과 무관하게 계속 실려 나간다. 그쪽은 FastAPI가 받아 두는
 * metadata이고 MVP에서는 임베딩 입력에 결합하지 않는다({@code ContextProcessRequest.PlaceMeta}).
 */
@Component
public class EmbeddingInputComposer {

	/**
	 * 장소명과 본문 사이의 구분자. {@code ai} 레포 측정 명세의 {@code f"{placeName}. {contextBody}"}와
	 * 바이트 단위로 같아야 한다 — 구분자가 다르면 다른 벡터가 나오고 조건 B·D의 수치가 재현되지 않는다.
	 */
	private static final String SEPARATOR = ". ";

	private final EmbeddingInputProperties properties;

	public EmbeddingInputComposer(EmbeddingInputProperties properties) {
		this.properties = properties;
	}

	/**
	 * 스위치가 꺼져 있으면 {@code contextBody}를 <b>손대지 않고 그대로</b> 돌려준다. 이것이 측정 조건
	 * A(현행 기준선)의 재현 조건이므로 trim·정규화도 하지 않는다 — 여기서 문자열을 한 글자라도 바꾸면
	 * 기준선 벡터가 달라져 기존 임베딩과 비교가 성립하지 않는다.
	 *
	 * <p>장소명이 없거나 공백뿐이면 켜져 있어도 본문만 돌려준다. 그대로 결합하면 {@code ". 본문"}처럼
	 * 앞에 빈 구분자가 붙은 문자열이 임베딩되는데, 그것은 두 조건 중 어느 쪽도 아닌 세 번째 입력이다.
	 *
	 * @param placeName Place 마스터의 장소명. Place가 없거나 이름이 비어 있을 수 있다
	 * @param contextBody Core 본문 그대로
	 */
	public String compose(@Nullable String placeName, String contextBody) {
		if (!properties.includePlaceName()) {
			return contextBody;
		}
		if (placeName == null || placeName.isBlank()) {
			return contextBody;
		}
		return placeName + SEPARATOR + contextBody;
	}
}
