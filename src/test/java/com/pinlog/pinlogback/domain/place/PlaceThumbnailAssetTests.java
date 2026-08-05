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
 * place 썸네일 정적 서빙 경로 검증(S15P11A705-305). core.place.thumbnail_url에 저장하는
 * 경로 형태(/images/places/*)가 인증 없이 서빙되는지를 본다 — SecurityConfig의
 * PUBLIC_STATIC_ASSETS가 빠지면 <img> 요청이 401로 깨지는데, 그 회귀를 여기서 잡는다.
 *
 * <p>이미지는 테스트 리소스의 더미다. 시연용 실제 이미지(표지)는 나올 때
 * src/main/resources/static/images/places/test/에 같은 파일명 규약으로 커밋한다(S15P11A705-321).
 * test/ 하위여도 PUBLIC_STATIC_ASSETS가 /images/places/**로 덮으므로 시큐리티 설정은 그대로다.
 *
 * <p>WebP로 서빙되는지까지 본다. 계약은 4:3·가로 1200px이고 포맷은 프론트 계약이 아니지만,
 * MIME 매핑이 없으면 octet-stream으로 나가므로 Content-Type을 고정해 그 회귀를 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlaceThumbnailAssetTests extends IntegrationContainerSupport {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void demoThumbnailIsServedUnderImagesPlaces() throws Exception {
		mockMvc.perform(get("/images/places/cafe-1.webp"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("image/webp")));
	}
}
