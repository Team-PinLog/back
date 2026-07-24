package com.pinlog.pinlogback.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI pinlogOpenAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("PinLog Core API")
				.version("0.0.1-SNAPSHOT")
				.description("PinLog 백엔드 코어 API 문서"));
	}
}
