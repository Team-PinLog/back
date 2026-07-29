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
 * <p>Context는 불변이므로 재조회한 본문은 이벤트 발행 시점의 본문과 항상 같다. 그 사이 사용자가
 * 수정했다면 이 {@code context_id}는 이미 소프트 삭제·CANCELLED이고 신 {@code context_id}에 대한
 * 별도 이벤트가 발행되어 있으므로, 삭제된 것을 만나면 호출을 생략하면 된다.
 */
@Service
public class ContextProcessRequestAssembler {

	private final ContextRepository contextRepository;
	private final RecordRepository recordRepository;
	private final PlaceRepository placeRepository;

	public ContextProcessRequestAssembler(ContextRepository contextRepository, RecordRepository recordRepository,
		PlaceRepository placeRepository) {
		this.contextRepository = contextRepository;
		this.recordRepository = recordRepository;
		this.placeRepository = placeRepository;
	}

	/**
	 * 조립할 수 없으면 {@link Optional#empty()}다. 예외가 아닌 이유: 커밋과 이 조회 사이에 삭제가
	 * 끼어드는 것은 <b>정상 경로</b>이며(수정·삭제 요청), 그때 할 일은 호출을 하지 않는 것뿐이다.
	 */
	@Transactional(readOnly = true)
	public Optional<ContextProcessRequest> assemble(ContextAiRequested event) {
		Optional<Context> context = contextRepository.findById(event.contextId());
		if (context.isEmpty()) {
			return Optional.empty();
		}
		// Place는 Record를 거쳐야 나온다. Record까지 지워졌으면 placeMeta 없이 보내지 않고 생략한다 —
		// Record가 없다는 것은 이 Context도 이미 함께 지워졌다는 뜻이다(연쇄 삭제).
		Optional<Record> record = recordRepository.findById(event.recordId());
		if (record.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new ContextProcessRequest(
			event.contextId(),
			event.memberId(),
			event.recordId(),
			context.get().getBody(),
			placeMetaOf(record.get())
		));
	}

	private ContextProcessRequest.PlaceMeta placeMetaOf(Record record) {
		Place place = placeRepository.findById(record.getPlaceId()).orElseThrow();
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
