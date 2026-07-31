package com.pinlog.pinlogback.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 임베딩 입력 구성. Spring Context를 올리지 않는다 — 설정 record 하나에만 의존하는 순수 함수다.
 *
 * <p>이 클래스가 실제로 지키는 것은 <b>기본값이 현행과 한 글자도 다르지 않다</b>는 성질이다. 임베딩
 * 4조건 측정의 기준선(조건 A)이 그 성질 위에 서 있어서, 여기가 무너지면 나머지 세 조건의 수치도
 * 비교 대상을 잃는다.
 */
class EmbeddingInputComposerTest {

	private static final String PLACE_NAME = "동교어린이공원";
	private static final String BODY = "그네팟 스팟. 밥먹고 산책하면서 여기 머물다가 가기 좋음";

	private static EmbeddingInputComposer composerWith(boolean includePlaceName) {
		return new EmbeddingInputComposer(new EmbeddingInputProperties(includePlaceName));
	}

	@Nested
	@DisplayName("기본값(끔) — 현행 동작")
	class Disabled {

		@Test
		@DisplayName("본문을 그대로 돌려준다")
		void passesBodyThrough() {
			assertThat(composerWith(false).compose(PLACE_NAME, BODY)).isEqualTo(BODY);
		}

		/**
		 * 같은 입력이면 같은 벡터라는 것이 임베딩의 성질이므로, 역으로 <b>입력이 한 글자라도 달라지면</b>
		 * 기존에 저장된 임베딩과 비교가 성립하지 않는다. trim·정규화조차 하지 않는 것을 못박아 둔다.
		 */
		@ParameterizedTest
		@ValueSource(strings = {"  앞뒤 공백이 있는 본문  ", "\n개행으로 시작", "이모지 🍕 포함", ""})
		@DisplayName("공백·개행·이모지·빈 문자열을 정규화하지 않는다")
		void doesNotNormalize(String body) {
			assertThat(composerWith(false).compose(PLACE_NAME, body)).isSameAs(body);
		}

		@Test
		@DisplayName("장소명이 없어도 본문 그대로다")
		void ignoresMissingPlaceName() {
			assertThat(composerWith(false).compose(null, BODY)).isEqualTo(BODY);
		}
	}

	@Nested
	@DisplayName("켬 — 측정 조건 B·D")
	class Enabled {

		/**
		 * 구분자는 {@code ai} 레포 측정 명세의 {@code f"{placeName}. {contextBody}"}와 바이트 단위로
		 * 같아야 한다. 리터럴로 적어 두는 이유는 상수를 참조하면 상수가 바뀔 때 테스트가 함께 따라가
		 * 아무것도 잡지 못하기 때문이다.
		 */
		@Test
		@DisplayName("\"장소명. 본문\"으로 결합한다")
		void prependsPlaceName() {
			assertThat(composerWith(true).compose(PLACE_NAME, BODY))
				.isEqualTo("동교어린이공원. 그네팟 스팟. 밥먹고 산책하면서 여기 머물다가 가기 좋음");
		}

		@Test
		@DisplayName("본문에 이미 있는 구두점·중복 장소명을 손대지 않는다")
		void doesNotDeduplicate() {
			assertThat(composerWith(true).compose("치킨버거 이스트사이드", "치킨버거 맛있더라."))
				.isEqualTo("치킨버거 이스트사이드. 치킨버거 맛있더라.");
		}

		/**
		 * 장소명이 없을 때 그대로 결합하면 {@code ". 본문"}이 임베딩되는데, 그것은 A도 B도 아닌 제3의
		 * 입력이라 어느 조건의 수치도 아니게 된다.
		 */
		@ParameterizedTest
		@ValueSource(strings = {"", " ", "\t", "\n"})
		@DisplayName("장소명이 비어 있으면 빈 구분자를 앞에 붙이지 않는다")
		void skipsBlankPlaceName(String placeName) {
			assertThat(composerWith(true).compose(placeName, BODY)).isEqualTo(BODY);
		}

		@Test
		@DisplayName("장소명이 null이면 본문 그대로다")
		void skipsNullPlaceName() {
			assertThat(composerWith(true).compose(null, BODY)).isEqualTo(BODY);
		}
	}
}
