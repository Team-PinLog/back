package com.pinlog.pinlogback.domain.place;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 시연용 place 썸네일 정적 리소스(S15P11A705-305). 프레임워크의 정적 서빙이 아니라
 * 우리가 넣은 파일이 약속한 경로(core.place.thumbnail_url에 저장하는 값)에 실제로
 * 존재하는지를 검증한다 — 파일이 이동·누락되면 여기서 잡힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlaceThumbnailAssetTests extends IntegrationContainerSupport {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void demoThumbnailIsServedUnderImagesPlaces() throws Exception {
		mockMvc.perform(get("/images/places/cafe-1.jpg"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG));
	}
}
