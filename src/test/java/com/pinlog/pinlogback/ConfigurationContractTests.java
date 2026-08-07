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
 * <p>datasource·redis 접속 정보는 여기서 검증하지 않는다 — {@code application-prod.yml}에 두지 않고
 * infra가 {@code SPRING_DATASOURCE_*}·{@code SPRING_DATA_REDIS_*} 표준 이름으로 주입하는 환경변수에
 * 전적으로 맡기기로 했다(BD-50). 이 파일에 리터럴을 다시 쓰면 그 결정을 되돌리는 것이다.
 */
class ConfigurationContractTests {

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

	/**
	 * datasource·redis는 infra가 표준 이름 환경변수로 전부 주입하므로(BD-50), 이 파일에
	 * {@code spring.datasource.*}·{@code spring.data.redis.*}를 다시 적지 않는다 — 적어봤자
	 * OS 환경변수 우선순위에 밀려 무시되고, 리터럴이 실제 접속 정보와 어긋나도 아무도 모른다.
	 */
	@Test
	void prodProfileDoesNotRedeclareDatasourceOrRedisSettings() throws IOException {
		Map<String, Object> prod = load("application-prod.yml");

		assertThat(prod.keySet())
			.as("infra 환경변수가 이미 주입하는 키를 이 파일에서 다시 선언하지 않는다(BD-50)")
			.noneMatch(key -> key.startsWith("spring.datasource.") || key.startsWith("spring.data.redis."));
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

	/**
	 * 재스캔 파라미터는 AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 2장이 정본이다.
	 * 값을 파일 자체로 고정하는 이유는 <b>어긋나도 아무 테스트가 깨지지 않기</b> 때문이다 — 만료를
	 * 검증하는 통합 테스트는 자기 임계값을 덮어 쓰므로 기본값이 무엇이든 통과한다.
	 *
	 * <p>{@code interval}만 ISO-8601인 것은 실수가 아니다. 이 값은 Boot의 완화된 바인딩이 아니라
	 * {@code @Scheduled(fixedDelayString)}이 직접 파싱하고, 그쪽은 숫자(밀리초)나 ISO-8601만 받는다.
	 * {@code 5m}으로 적으면 {@code NumberFormatException}으로 기동이 실패한다.
	 */
	@Test
	void theRescanParametersMatchTheOwningSpec() throws IOException {
		Map<String, Object> defaults = load("application.yml");

		assertThat(defaults.get("pinlog.ai.rescan.interval"))
			.as("@Scheduled가 직접 파싱하므로 5m이 아니라 ISO-8601이어야 한다")
			.isEqualTo("PT5M");
		assertThat(defaults.get("pinlog.ai.rescan.pending-expiry")).isEqualTo("5m");
		assertThat(defaults.get("pinlog.ai.rescan.processing-expiry"))
			.as("실제로 처리 중일 가능성을 고려해 PENDING보다 길다")
			.isEqualTo("10m");
		assertThat(String.valueOf(defaults.get("pinlog.ai.rescan.max-retry")))
			.as("정본은 DB의 CHECK (retry_count BETWEEN 0 AND 3)이다 — 올리면 증가 UPDATE가 실패한다")
			.isEqualTo("3");
		assertThat(String.valueOf(defaults.get("pinlog.ai.rescan.batch-size"))).isEqualTo("100");
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
