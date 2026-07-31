package com.pinlog.pinlogback.domain.ai.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.ai.client.ContextProcessRequest;
import com.pinlog.pinlogback.domain.ai.event.ContextAiRequested;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;

/**
 * 커밋된 {@code context_id}로 {@code process} 요청 본문을 다시 읽어 조립한다
 * (AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 4.2·4.4).
 *
 * <p><b>본문을 이벤트에 실어 나르지 않고 여기서 다시 읽는 것이 요점이다.</b> 명세 4.4는 "같은
 * {@code context_id}로 다른 {@code text}를 보내는 것은 계약 위반"이라고 못박는데, 다른 곳에서
 * 넘어온 문자열을 그대로 실으면 그 계약을 코드로 보장할 방법이 없다. Core 본문을 그대로 조회해
 * 채우면 위반이 구조적으로 불가능해진다.
 *
 * <p>{@link EmbeddingInputComposer}는 그 재조회한 본문을 <b>기본값에서 손대지 않고</b> 통과시키므로
 * 위 성질이 유지된다. 스위치를 켰을 때만 장소명이 결합되고, 그때 명세 4.4와 충돌이 생긴다 —
 * 미해소로 표시해 두었으니 {@code docs/ai/spec/ai-integration.md} 4.4의 주석을 함께 읽어라.
 *
 * <p>Context는 불변이므로 재조회한 본문은 이벤트 발행 시점의 본문과 항상 같다. 그 사이 사용자가
 * 수정했다면 이 {@code context_id}는 이미 소프트 삭제·CANCELLED이고 신 {@code context_id}에 대한
 * 별도 이벤트가 발행되어 있으므로, 삭제된 것을 만나면 호출을 생략하면 된다.
 */
@Service
public class ContextProcessRequestAssembler {

	private final ContextRepository contextRepository;
	private final RecordRepository recordRepository;
	private final PlaceRepository placeRepository;
	private final EmbeddingInputComposer embeddingInputComposer;

	public ContextProcessRequestAssembler(ContextRepository contextRepository, RecordRepository recordRepository,
		PlaceRepository placeRepository, EmbeddingInputComposer embeddingInputComposer) {
		this.contextRepository = contextRepository;
		this.recordRepository = recordRepository;
		this.placeRepository = placeRepository;
		this.embeddingInputComposer = embeddingInputComposer;
	}

	/**
	 * 조립할 수 없으면 {@link Optional#empty()}다. 예외가 아닌 이유: 커밋과 이 조회 사이에 삭제가
	 * 끼어드는 것은 <b>정상 경로</b>이며(수정·삭제 요청), 그때 할 일은 호출을 하지 않는 것뿐이다.
	 */
	@Transactional(readOnly = true)
	public Optional<ContextProcessRequest> assemble(ContextAiRequested event) {
		return contextRepository.findById(event.contextId())
			.flatMap(context -> assembleFrom(context, event.memberId(), event.recordId()));
	}

	/**
	 * {@code context_id}만 들고 조립한다. 재스캔이 쓰는 진입점이다
	 * (AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 5.1).
	 *
	 * <p>이벤트 경로와 갈라 둔 이유는 <b>가진 정보가 다르기</b> 때문이다. 재스캔은 상태 행에서 후보를
	 * 집으므로 {@code context_id}뿐이고, {@code member_id}·{@code record_id}는 Context의 비정규화
	 * 컬럼에서 읽는다. 반대로 이벤트 경로는 발행 시점의 값을 그대로 쓴다 — 그쪽을 이 메서드로 바꾸면
	 * 같은 값을 두 번 읽게 되고, 무엇보다 <b>이미 검증된 경로의 동작을 이 티켓이 건드리게 된다.</b>
	 *
	 * <p>Context가 없으면(소프트 삭제·수정 교체) {@link Optional#empty()}다. 이것이 재스캔에서
	 * <b>삭제 확인</b> 그 자체다 — 별도 질의를 두지 않는다.
	 */
	@Transactional(readOnly = true)
	public Optional<ContextProcessRequest> assemble(long contextId) {
		return contextRepository.findById(contextId)
			.flatMap(context -> assembleFrom(context, context.getMemberId(), context.getRecordId()));
	}

	private Optional<ContextProcessRequest> assembleFrom(Context context, Long memberId, Long recordId) {
		// Place는 Record를 거쳐야 나온다. Record까지 지워졌으면 placeMeta 없이 보내지 않고 생략한다 —
		// Record가 없다는 것은 이 Context도 이미 함께 지워졌다는 뜻이다(연쇄 삭제).
		return recordRepository.findById(recordId)
			.map(record -> {
				// Place를 한 번만 읽어 text와 placeMeta 양쪽에 쓴다. 두 번 읽으면 그 사이에 장소명이
				// 바뀔 수 있어 "보낸 text의 장소명"과 "보낸 placeMeta.name"이 갈라진다.
				Place place = placeRepository.findById(record.getPlaceId()).orElseThrow();
				return new ContextProcessRequest(
					context.getId(),
					memberId,
					recordId,
					embeddingInputComposer.compose(place.getName(), context.getBody()),
					placeMetaOf(place)
				);
			});
	}

	private ContextProcessRequest.PlaceMeta placeMetaOf(Place place) {
		return new ContextProcessRequest.PlaceMeta(
			place.getId(),
			place.getName(),
			place.getAddress(),
			place.getRoadAddress(),
			place.getLat(),
			place.getLng()
		);
	}
}
