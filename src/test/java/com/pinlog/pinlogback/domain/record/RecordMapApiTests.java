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

@SpringBootTest
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
	void keywordFiltersByPlaceName() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-name-1", "롯데월드 어드벤처", "37.5111306", "127.0981198");
		saveMarker(memberId, "map-kw-name-2", "스타벅스 강남R점", "37.4976745", "127.0284434");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)).param("keyword", "롯데"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("롯데월드 어드벤처"));
	}

	@Test
	void keywordMatchesAddressToo() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-addr-1", "석촌호수 서호", "송파구 잠실동 47", "37.5076807", "127.0991128");
		saveMarker(memberId, "map-kw-addr-2", "양재천", "강남구 대치동 514", "37.4818038", "127.0465952");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)).param("keyword", "잠실"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("석촌호수 서호"));
	}

	@Test
	void keywordIsCaseInsensitive() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-case-1", "Apple 가로수길", "37.5208198", "127.0227294");
		saveMarker(memberId, "map-kw-case-2", "코엑스", "37.5118242", "127.0591586");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)).param("keyword", "apple"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("Apple 가로수길"));
	}

	@Test
	void blankKeywordReturnsAll() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-blank-1", "코엑스", "37.5118242", "127.0591586");
		saveMarker(memberId, "map-kw-blank-2", "예술의전당", "37.4794461", "127.0137536");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)).param("keyword", "   "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2));
	}

	@Test
	void keywordCombinesWithBboxAsAnd() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-bbox-1", "롯데월드 어드벤처", "37.5111306", "127.0981198");
		saveMarker(memberId, "map-kw-bbox-2", "코엑스", "37.5118242", "127.0591586");
		saveMarker(memberId, "map-kw-bbox-3", "롯데백화점 부산본점", "35.1552490", "129.0595537");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId))
				.param("keyword", "롯데")
				.param("swLat", "37.4").param("swLng", "126.9")
				.param("neLat", "37.6").param("neLng", "127.2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("롯데월드 어드벤처"));
	}

	@Test
	void boundsShrinkToKeywordMatches() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-bounds-1", "롯데월드 어드벤처", "37.5111306", "127.0981198");
		saveMarker(memberId, "map-kw-bounds-2", "예술의전당", "37.4794461", "127.0137536");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)).param("keyword", "롯데"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.bounds.swLat").value(37.5111306))
			.andExpect(jsonPath("$.data.bounds.neLat").value(37.5111306))
			.andExpect(jsonPath("$.data.bounds.swLng").value(127.0981198))
			.andExpect(jsonPath("$.data.bounds.neLng").value(127.0981198));
	}

	@Test
	void keywordDoesNotExposeOtherMembersMarkers() throws Exception {
		long me = newMemberId();
		long other = newMemberId();
		saveMarker(other, "map-kw-other-1", "롯데월드 어드벤처", "37.5111306", "127.0981198");

		mockMvc.perform(get("/v1/records/map").with(loginAs(me)).param("keyword", "롯데"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void percentInKeywordIsLiteral() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-pct-1", "100%맛집", "37.5000000", "127.0000000");
		saveMarker(memberId, "map-kw-pct-2", "100번지식당", "37.5100000", "127.0100000");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)).param("keyword", "100%"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("100%맛집"));
	}

	@Test
	void underscoreInKeywordIsLiteral() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-kw-us-1", "cafe_44", "37.5000000", "127.0000000");
		saveMarker(memberId, "map-kw-us-2", "cafe 44", "37.5100000", "127.0100000");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)).param("keyword", "cafe_"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].name").value("cafe_44"));
	}

	@Test
	void itemsAreSortedByNameAscending() throws Exception {
		long memberId = newMemberId();
		saveMarker(memberId, "map-sort-1", "코엑스", "37.5118242", "127.0591586");
		saveMarker(memberId, "map-sort-2", "가락시장", "37.4938884", "127.1109273");
		saveMarker(memberId, "map-sort-3", "예술의전당", "37.4794461", "127.0137536");

		mockMvc.perform(get("/v1/records/map").with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].name").value("가락시장"))
			.andExpect(jsonPath("$.data.items[1].name").value("예술의전당"))
			.andExpect(jsonPath("$.data.items[2].name").value("코엑스"));
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
		saveMarker(memberId, kakaoPlaceId, name, "주소", lat, lng);
	}

	private void saveMarker(long memberId, String kakaoPlaceId, String name, String address, String lat, String lng) {
		Place place = placeRepository.save(Place.create(
			kakaoPlaceId, name, address, null, null, null, new BigDecimal(lat), new BigDecimal(lng)));
		recordRepository.save(Record.create(memberId, place.getId()));
	}
}
