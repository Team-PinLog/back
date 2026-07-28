package com.pinlog.pinlogback.global.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.pinlog.pinlogback.global.config.JwtProperties;

@DisplayName("JWT 서명 키 공급")
class JwtKeyProviderTest {

	private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
	private static final Duration REFRESH_TTL = Duration.ofDays(7);

	@Test
	@DisplayName("키가 없으면 로컬·테스트에서는 임시 키쌍을 만든다")
	void generatesEphemeralKeyOutsideProduction() throws Exception {
		JwtKeyProvider provider = new JwtKeyProvider(properties(null), new MockEnvironment());

		assertThat(provider.rsaKey().toRSAPrivateKey()).isNotNull();
		assertThat(provider.rsaKey().getKeyID()).isNotBlank();
	}

	@Test
	@DisplayName("운영 프로파일에서 키가 없으면 기동에 실패한다")
	void failsFastInProductionWithoutKey() {
		// 임시 키를 만들면 파드마다 서명 키가 달라져 스케일아웃·재시작 때 전면 로그아웃이 된다.
		// 조용히 망가지는 것보다 뜨지 않는 편이 낫다(BD-29).
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> new JwtKeyProvider(properties(null), prod))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_PRIVATE_KEY");
	}

	@Test
	@DisplayName("주입된 PEM으로 키를 만들고 공개키를 개인키에서 뽑는다")
	void readsInjectedPemAndDerivesPublicKey() throws Exception {
		String pem = generatePkcs8Pem();

		JwtKeyProvider provider = new JwtKeyProvider(properties(pem), new MockEnvironment());

		assertThat(provider.rsaKey().toRSAPublicKey().getModulus())
			.isEqualTo(provider.rsaKey().toRSAPrivateKey().getModulus());
	}

	@Test
	@DisplayName("같은 PEM은 항상 같은 kid를 만든다")
	void keyIdIsStableAcrossRestarts() throws Exception {
		// 파드마다 kid가 달라지면 나중에 회전을 붙일 때 이 값이 쓸모없어진다.
		String pem = generatePkcs8Pem();

		assertThat(new JwtKeyProvider(properties(pem), new MockEnvironment()).rsaKey().getKeyID())
			.isEqualTo(new JwtKeyProvider(properties(pem), new MockEnvironment()).rsaKey().getKeyID());
	}

	@Test
	@DisplayName("PEM이 깨져 있으면 기동에 실패하고 키 내용을 메시지에 담지 않는다")
	void failsOnMalformedPemWithoutLeakingIt() {
		String malformed = "-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----";

		assertThatThrownBy(() -> new JwtKeyProvider(properties(malformed), new MockEnvironment()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("pinlog.auth.jwt.private-key")
			.hasMessageNotContaining("not-a-key");
	}

	/** 키 미주입을 재현하려면 {@code null}을 넣어야 하므로 파라미터도 nullable이다. */
	private JwtProperties properties(@Nullable String privateKey) {
		return new JwtProperties(privateKey, ACCESS_TTL, REFRESH_TTL, "pinlog");
	}

	private String generatePkcs8Pem() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String body = Base64.getMimeEncoder()
			.encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
		return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
	}
}
