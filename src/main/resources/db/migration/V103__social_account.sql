-- core.social_account — 인증 수단. 신원(member)과 분리한다.
-- 근거: docs/static/06_데이터모델_및_무결성.md §2.2, docs/static/07_ERD.md §4.1.
--
-- 번호 규칙: 파트별 구간을 두지 않고 현재 적용된 최대 번호보다 큰 값을 쓴다.
-- 구간을 나누면 AI 구간(V100~)이 먼저 적용된 상태라 낮은 번호가 항상 순서를 위반해
-- Flyway 기동이 실패한다. 충돌하면 머지 시점에 다음 번호로 rename한다.
--
-- provider_user_id는 공급자가 발급한 식별자다(Google sub, Kakao id, Naver response.id).
-- 이메일·닉네임을 식별 기준으로 쓰지 않는다. 숫자로 보이는 값도 문자열로 저장한다.
-- email은 설정 화면 표시용이며 공급자 미제공·미동의 시 null이다. 식별키가 아니라 유니크가 없다.
CREATE TABLE core.social_account (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id        BIGINT       NOT NULL REFERENCES core.member (id),
    provider         VARCHAR(20)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);

-- 활성행 기준 부분 유니크. 전체 유니크로 정의하면 탈퇴 후 같은 소셜 계정으로 재가입할 수 없다.
CREATE UNIQUE INDEX ux_social_account_provider_user
    ON core.social_account (provider, provider_user_id)
    WHERE deleted_at IS NULL;

-- 회원 단위 조회(마이페이지 요약·탈퇴)에서 삭제분을 제외한다.
CREATE INDEX ix_social_account_member_active
    ON core.social_account (member_id)
    WHERE deleted_at IS NULL;
