package com.pinlog.pinlogback.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pinlog.pinlogback.domain.sample.EnvelopeTestController;

class ApiResponseBodyAdviceTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new EnvelopeTestController())
			.setControllerAdvice(new ApiResponseBodyAdvice())
			.build();
	}

	@Test
	void dtoIsWrappedInEnvelope() throws Exception {
		mockMvc.perform(get("/test-envelope/dto"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("pinlog"))
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void alreadyWrappedResponseIsNotDoubleWrapped() throws Exception {
		mockMvc.perform(get("/test-envelope/already-wrapped"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("pinlog"))
			.andExpect(jsonPath("$.data.data").doesNotExist());
	}

	/**
	 * {@code ResponseEntity<ApiResponse<T>>}도 이중 래핑되지 않는다. 문서 쪽 같은 케이스를
	 * {@code ApiResponseOpenApiCustomizerTest}가 검증하며, <b>두 테스트가 같은 결론을 요구하는 것</b>이
	 * 문서와 실제 응답을 붙여 두는 장치다(S15P11A705-85).
	 */
	@Test
	void responseEntityWrappedEnvelopeIsNotDoubleWrapped() throws Exception {
		mockMvc.perform(get("/test-envelope/entity-wrapped"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("pinlog"))
			.andExpect(jsonPath("$.data.data").doesNotExist())
			.andExpect(jsonPath("$.data.success").doesNotExist());
	}

	// 이 테스트와 noContentEntityHasEmptyBody 둘 다 advice의 null 분기(beforeBodyWrite의
	// body == null 처리)를 실제로 통과한다 — body==null 분기를 임시로 ApiResponse.ok(null)로
	// 바꿔 재실행해 두 테스트가 함께 실패하는 것으로 직접 확인했다(진단용 로그로 beforeBodyWrite가
	// noContent()·noContentEntity() 양쪽 handler에서 null body로 호출됨을 검증). 이 프로젝트가
	// 쓰는 Spring Framework 7.0.8 + standalone MockMvc 조합에서는 bare void 반환도
	// RequestResponseBodyMethodProcessor의 컨버터 루프를 타고 advice까지 도달한다.
	@Test
	void voidResponseHasEmptyBody() throws Exception {
		mockMvc.perform(get("/test-envelope/void"))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
	}

	@Test
	void noContentEntityHasEmptyBody() throws Exception {
		mockMvc.perform(get("/test-envelope/no-content"))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));
	}

	@Test
	void beforeBodyWriteReturnsNullForNullBody() {
		Object wrapped = new ApiResponseBodyAdvice().beforeBodyWrite(null, null, null, null, null, null);

		assertThat(wrapped).isNull();
	}
}
