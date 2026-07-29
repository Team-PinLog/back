package com.pinlog.pinlogback.domain.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * 공급자 응답 정규화를 Spring Context 없이 검증한다.
 *
 * <p>HTTP 경계 테스트({@code KakaoNaverLoginCallbackTests})는 <b>값이 있는 경로</b>만 지난다.
 * 값이 <b>없을 때 끊는 쪽</b>은 그쪽으로 태울 수 없다 — 공급자 대역이 규격 밖 응답을 내도록
 * 만들면 Spring 내부에서 먼저 죽어 우리 코드에 도달하지 않는 경우가 있고(BI-18에 기록),
 * 도달하더라도 실패가 리다이렉트로 뭉개져 어느 단언이 무엇을 잡았는지 알기 어렵다.
 *
 * <p>그래서 오류 경로는 여기서 본다. 순수 단위 테스트에는 Context를 올리지 않는다는
 * <a href="../../../../../../../../docs/development/testing-conventions.md">테스트 규약</a>에도 맞다.
 */
@DisplayName("공급자 응답 정규화")
class OAuthUserInfoTest {

	private static final String EMAIL = "tester@example.com";

	@Nested
	@DisplayName("Google")
	class Google {

		@Test
		@DisplayName("최상위 sub·email을 꺼낸다")
		void extractsTopLevelAttributes() {
			OAuthUserInfo info = OAuthUserInfo.from("google", Map.of("sub", "google-sub-1", "email", EMAIL));

			assertThat(info.provider()).isEqualTo(SocialProvider.GOOGLE);
			assertThat(info.providerUserId()).isEqualTo("google-sub-1");
			assertThat(info.email()).isEqualTo(EMAIL);
		}

		@Test
		@DisplayName("sub이 없으면 끊는다")
		void rejectsMissingSub() {
			assertThatThrownBy(() -> OAuthUserInfo.from("google", Map.of("email", EMAIL)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("sub");
		}
	}

	@Nested
	@DisplayName("Kakao")
	class Kakao {

		@Test
		@DisplayName("숫자 id를 문자열로, 이메일은 kakao_account 아래에서 꺼낸다")
		void extractsNumericIdAsStringAndNestedEmail() {
			// 카카오는 id를 JSON 숫자로 준다. provider_user_id는 항상 문자열이다(06 2.2).
			OAuthUserInfo info = OAuthUserInfo.from(
				"kakao", Map.of("id", 5_013_244_578L, "kakao_account", Map.of("email", EMAIL)));

			assertThat(info.provider()).isEqualTo(SocialProvider.KAKAO);
			assertThat(info.providerUserId()).isEqualTo("5013244578");
			assertThat(info.email()).isEqualTo(EMAIL);
		}

		@Test
		@DisplayName("kakao_account가 없어도 가입은 성공한다")
		void allowsMissingEmailWrapper() {
			// 이메일 동의는 선택이고 철회도 가능하다. 이메일 없음은 실패가 아니다.
			OAuthUserInfo info = OAuthUserInfo.from("kakao", Map.of("id", 42L));

			assertThat(info.providerUserId()).isEqualTo("42");
			assertThat(info.email()).isNull();
		}

		@Test
		@DisplayName("id가 없으면 끊는다")
		void rejectsMissingId() {
			assertThatThrownBy(() -> OAuthUserInfo.from("kakao", Map.of("kakao_account", Map.of("email", EMAIL))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("id");
		}
	}

	@Nested
	@DisplayName("Naver")
	class Naver {

		@Test
		@DisplayName("response로 한 겹 감싼 안에서 id·email을 꺼낸다")
		void extractsFromWrapper() {
			OAuthUserInfo info = OAuthUserInfo.from("naver", Map.of(
				"resultcode", "00",
				"message", "success",
				"response", Map.of("id", "naver-id-1", "email", EMAIL)));

			assertThat(info.provider()).isEqualTo(SocialProvider.NAVER);
			assertThat(info.providerUserId()).isEqualTo("naver-id-1");
			assertThat(info.email()).isEqualTo(EMAIL);
		}

		@Test
		@DisplayName("response 안에 id가 없으면 끊는다")
		void rejectsMissingIdInsideWrapper() {
			// 이 단언이 지키는 것은 Naver에만 있는 공백이다. user-name-attribute가 감싼 키
			// (response)라서 Spring의 DefaultOAuth2User는 그 Map의 존재만 확인하고 안의 id는
			// 보지 않는다. Google·Kakao는 최상위 스칼라를 지목해 Spring이 앞단에서 걸러 주지만
			// Naver만 그 보증이 없어, 여기서 끊지 않으면 provider_user_id가 null로 내려가
			// NOT NULL 위반이 되고 원인이 DB까지 내려간다.
			assertThatThrownBy(() -> OAuthUserInfo.from("naver", Map.of(
				"resultcode", "00",
				"response", Map.of())))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("response.id");
		}

		@Test
		@DisplayName("response 자체가 없으면 끊는다")
		void rejectsMissingWrapper() {
			assertThatThrownBy(() -> OAuthUserInfo.from("naver", Map.of("resultcode", "00")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("response.id");
		}

		@Test
		@DisplayName("response 안에 email이 없어도 가입은 성공한다")
		void allowsMissingEmailInsideWrapper() {
			OAuthUserInfo info = OAuthUserInfo.from("naver", Map.of("response", Map.of("id", "naver-id-2")));

			assertThat(info.email()).isNull();
		}
	}

	@Test
	@DisplayName("지원하지 않는 공급자는 UnsupportedSocialProviderException이다")
	void rejectsUnknownProvider() {
		// 진입 경로(SocialLoginController)와 같은 예외를 써야 404 계약이 한 벌로 유지된다.
		assertThatThrownBy(() -> OAuthUserInfo.from("apple", Map.of("sub", "x")))
			.isInstanceOf(UnsupportedSocialProviderException.class);
	}
}
