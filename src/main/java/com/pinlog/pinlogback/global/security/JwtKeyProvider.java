package com.pinlog.pinlogback.global.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.pinlog.pinlogback.global.config.JwtProperties;

/**
 * 세션 JWT 서명 키를 공급한다(BD-29).
 *
 * <p>키를 주입받지 못했을 때 동작이 프로파일에 따라 갈린다.
 *
 * <ul>
 *   <li><b>운영</b> — 기동에 실패한다. 임시 키를 만들면 파드마다 서명 키가 달라져 스케일아웃·
 *       재시작 때 전면 로그아웃이 된다. 조용히 망가지는 것보다 뜨지 않는 편이 낫다.</li>
 *   <li><b>그 외</b> — 임시 키쌍을 만든다. {@code ${GOOGLE_CLIENT_ID:unset}}처럼 더미 문자열로
 *       때울 수 없어서다 — PEM은 파싱 가능한 키여야 한다. 인증을 <b>우회</b>하는 것이 아니라
 *       키를 <b>공급</b>하는 것이므로 "로컬에서 인증을 통째로 우회하지 않는다"는 계약과 어긋나지
 *       않는다(authentication.md 4).</li>
 * </ul>
 *
 * <p>{@code kid}는 공개키 thumbprint에서 뽑는다. 난수로 만들면 같은 키를 쓰는 파드끼리 서로 다른
 * {@code kid}를 달게 되어 나중에 회전을 붙일 때 값이 없어진다.
 */
@Component
public class JwtKeyProvider {

	private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);
	private static final String PROD_PROFILE = "prod";
	private static final int EPHEMERAL_KEY_SIZE = 2048;

	private final RSAKey rsaKey;

	public JwtKeyProvider(JwtProperties properties, Environment environment) {
		// 지역 변수로 받아야 "비어 있지 않음"이 타입에 남는다. hasPrivateKey() 같은 술어 메서드는
		// 검사와 사용 사이의 연결을 컴파일러에 알려 주지 못해 nullable을 non-null 자리에 넘기게 된다.
		String privateKey = properties.privateKey();
		this.rsaKey = privateKey == null || privateKey.isBlank()
			? ephemeral(environment)
			: fromPem(privateKey);
	}

	public RSAKey rsaKey() {
		return rsaKey;
	}

	private static RSAKey fromPem(String pem) {
		String base64 = pem
			.replace("-----BEGIN PRIVATE KEY-----", "")
			.replace("-----END PRIVATE KEY-----", "")
			.replaceAll("\\s", "");
		try {
			byte[] der = Base64.getDecoder().decode(base64);
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			RSAPrivateKey privateKey =
				(RSAPrivateKey)keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
			return withThumbprintKeyId(derivePublicKey(keyFactory, privateKey), privateKey);
		} catch (IllegalArgumentException | InvalidKeySpecException | NoSuchAlgorithmException e) {
			// 키 내용은 로그에 남기지 않는다.
			throw new IllegalStateException(
				"pinlog.auth.jwt.private-key를 RSA PKCS#8 PEM으로 읽지 못했다", e);
		}
	}

	/**
	 * 공개키를 따로 주입받지 않고 개인키에서 뽑는다. CRT 형식 개인키가 modulus와 public exponent를
	 * 이미 들고 있어서, 설정 항목을 둘로 늘릴 이유가 없다.
	 */
	private static RSAPublicKey derivePublicKey(KeyFactory keyFactory, RSAPrivateKey privateKey)
		throws InvalidKeySpecException {
		if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
			throw new IllegalStateException(
				"공개키를 뽑을 수 없는 RSA 개인키다. PKCS#8 CRT 형식으로 주입해야 한다");
		}
		return (RSAPublicKey)keyFactory.generatePublic(
			new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
	}

	private static RSAKey ephemeral(Environment environment) {
		if (environment.matchesProfiles(PROD_PROFILE)) {
			throw new IllegalStateException(
				"운영 프로파일에는 pinlog.auth.jwt.private-key(JWT_PRIVATE_KEY)가 필요하다. "
					+ "임시 키를 만들면 파드마다 서명 키가 달라져 재시작·스케일아웃 때 전면 로그아웃이 된다");
		}
		log.warn("JWT 서명 키가 주입되지 않아 임시 키쌍을 생성한다. 재시작하면 기존 토큰이 모두 무효가 된다.");
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(EPHEMERAL_KEY_SIZE);
			KeyPair keyPair = generator.generateKeyPair();
			return withThumbprintKeyId(
				(RSAPublicKey)keyPair.getPublic(), (RSAPrivateKey)keyPair.getPrivate());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("RSA 키쌍을 생성하지 못했다", e);
		}
	}

	private static RSAKey withThumbprintKeyId(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
		try {
			return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
		} catch (JOSEException e) {
			throw new IllegalStateException("공개키 thumbprint를 계산하지 못했다", e);
		}
	}
}
