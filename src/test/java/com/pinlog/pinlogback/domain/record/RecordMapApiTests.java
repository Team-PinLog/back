package com.pinlog.pinlogback.domain.record;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

@SpringBootTest(properties = {
	"management.health.redis.enabled=false",
	"pinlog.auth.stub.enabled=true"
})
@AutoConfigureMockMvc
class RecordMapApiTests extends IntegrationContainerSupport {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Test
	void mapWithoutBboxReturnsAllMyMarkersWithEnclosingBounds() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-all-1", "남서쪽", "37.4000000", "126.9000000");
		saveMarker(memberId, "map-all-2", "북동쪽", "37.6000000", "127.1000000");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.bounds.swLat").value(37.4))
			.andExpect(jsonPath("$.data.bounds.swLng").value(126.9))
			.andExpect(jsonPath("$.data.bounds.neLat").value(37.6))
			.andExpect(jsonPath("$.data.bounds.neLng").value(127.1));
	}

	@Test
	void mapWithBboxReturnsOnlyMarkersInside() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-bbox-in", "안쪽", "37.5000000", "127.0000000");
		saveMarker(memberId, "map-bbox-out", "바깥쪽", "35.0000000", "129.0000000");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId))
				.param("swLat", "37.4").param("swLng", "126.9")
				.param("neLat", "37.6").param("neLng", "127.1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("안쪽"));
	}

	@Test
	void mapWithNoMarkersReturnsNullBounds() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.bounds").value(Matchers.nullValue()));
	}

	@Test
	void mapWithSingleMarkerReturnsPointBounds() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-single-1", "한 곳", "37.5447000", "127.0557000");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.bounds.swLat").value(37.5447))
			.andExpect(jsonPath("$.data.bounds.neLat").value(37.5447))
			.andExpect(jsonPath("$.data.bounds.swLng").value(127.0557))
			.andExpect(jsonPath("$.data.bounds.neLng").value(127.0557));
	}

	@Test
	void mapExcludesOtherMembersMarkers() throws Exception {
		long me = newMemberId();
		long other = newMemberId();
		saveMarker(other, "map-other-1", "남의 마커", "37.5000000", "127.0000000");

		mockMvc.perform(get("/v1/records/map").with(loginAs(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void partialBboxIs400() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId))
				.param("swLat", "37.4"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private void saveMarker(long memberId, String kakaoPlaceId, String name, String lat, String lng) {
		Place place = placeRepository.save(Place.create(
			kakaoPlaceId, name, "주소", null, null, null, new BigDecimal(lat), new BigDecimal(lng)));
		recordRepository.save(Record.create(memberId, place.getId()));
	}
}
