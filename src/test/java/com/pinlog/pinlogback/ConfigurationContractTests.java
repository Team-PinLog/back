package com.pinlog.pinlogback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 설정 파일이 담고 있어야 하는 계약을 파일 자체로 검증한다.
 *
 * <p>Spring Context를 올리지 않는 이유: 운영 프로파일의 주소는 클러스터 내부 DNS라서 컨텍스트를
 * 띄우면 접속을 시도하다 실패한다. 여기서 확인하려는 것은 "연결이 되는가"가 아니라
 * <b>"저장소에 적힌 값이 인프라 계약과 같은가"</b>이므로 YAML을 직접 읽는 편이 정확하고 빠르다.
 *
 * <p>계약 원본은 <a href="https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md">
 * infra/backend-conventions</a> 5장이며, 백엔드 쪽 규약은 {@code docs/development/configuration.md}다.
 */
class ConfigurationContractTests {

	private static final String PROD_DATASOURCE_URL =
		"jdbc:postgresql://postgres.pinlog-prod.svc.cluster.local:5432/pinlog";
	private static final String PROD_REDIS_HOST = "redis.pinlog-prod.svc.cluster.local";

	/**
	 * {@code open-in-view}를 끄는 이유: 기본값 true는 서비스 계층 밖(뷰·컨트롤러)에서도 영속성 컨텍스트를
	 * 열어 두므로, 도메인이 붙기 시작하면 컨트롤러에서 지연 로딩이 일어나 N+1이 조용히 생긴다.
	 */
	@Test
	void openInViewIsDisabled() throws IOException {
		assertThat(load("application.yml").get("spring.jpa.open-in-view"))
			.as("기본값(true)은 서비스 계층 밖에서 지연 로딩을 허용해 N+1을 조용히 만든다")
			.isEqualTo(false);
	}

	@Test
	void prodProfileCarriesClusterAddressesFromTheInfraContract() throws IOException {
		Map<String, Object> prod = load("application-prod.yml");

		assertThat(prod.get("spring.datasource.url")).isEqualTo(PROD_DATASOURCE_URL);
		assertThat(prod.get("spring.datasource.username")).isEqualTo("pinlog");
		assertThat(prod.get("spring.data.redis.host")).isEqualTo(PROD_REDIS_HOST);
		assertThat(String.valueOf(prod.get("spring.data.redis.port"))).isEqualTo("6379");
	}

	/**
	 * 저장소는 public이므로 비밀값이 파일에 들어가면 그대로 공개된다. 인프라가 주입하는 값은
	 * {@code DB_PASSWORD} 하나이므로, 비밀번호는 반드시 그 placeholder여야 한다.
	 */
	@Test
	void onlyThePasswordIsInjectedAndItIsNotHardcoded() throws IOException {
		Object password = load("application-prod.yml").get("spring.datasource.password");

		assertThat(password)
			.as("인프라 계약이 주입하는 유일한 비밀값 — 실제 비밀번호를 파일에 적으면 공개된다")
			.isEqualTo("${DB_PASSWORD}");
	}

	/**
	 * BD-39를 파일 자체로 고정한다. 두 가지가 함께 참이어야 결정이 지켜진 것이다.
	 *
	 * <ul>
	 *   <li><b>리터럴이 파일에 있다</b> — 정본이 코드에 있어야 Profile 교체가 PR·리뷰·git 이력을
	 *       거친다. 값은 비밀이 아니라 공용 계약 05 §7.1 표에 공개된 문자열이다.</li>
	 *   <li><b>기본값이 있다</b> — 환경변수는 실험·롤백용 덮어쓰기이지 필수가 아니다. 필수로 두면
	 *       주입 누락이 <b>빈 Profile 전송</b>이 되어 모든 검색이 422로 죽는다.</li>
	 * </ul>
	 *
	 * <p>이 값이 FastAPI 설정과 어긋나면 검색은 빈 결과가 아니라 오류가 된다(런타임 대조). 그 동작은
	 * {@code RecordSearchApiTests}가 따로 고정한다 — 여기서 보는 것은 "무엇을 보내는가"뿐이다.
	 */
	@Test
	void theEmbeddingProfileIsCommittedAsALiteralWithAnOptionalEnvironmentOverride() throws IOException {
		Object profile = load("application.yml").get("pinlog.ai.embedding-profile");

		assertThat(profile)
			.as("정본은 코드다 — 배포 콘솔 편집 한 번으로 기존 임베딩 전체가 조회 대상에서 빠지면 안 된다(BD-39)")
			.isEqualTo("${PINLOG_AI_EMBEDDING_PROFILE:openai-text-embedding-3-small-1536-cosine-v1}");
	}

	@Test
	void prodProfileStillHidesApiDocumentation() throws IOException {
		Map<String, Object> prod = load("application-prod.yml");

		assertThat(prod.get("springdoc.api-docs.enabled")).isEqualTo(false);
		assertThat(prod.get("springdoc.swagger-ui.enabled")).isEqualTo(false);
	}

	private Map<String, Object> load(String fileName) throws IOException {
		List<PropertySource<?>> sources =
			new YamlPropertySourceLoader().load(fileName, new ClassPathResource(fileName));
		Map<String, Object> flattened = new LinkedHashMap<>();
		for (PropertySource<?> source : sources) {
			for (String name : ((EnumerablePropertySource<?>)source).getPropertyNames()) {
				flattened.put(name, source.getProperty(name));
			}
		}
		return flattened;
	}
}
