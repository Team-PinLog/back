-- core.member — 백엔드 소유 첫 도메인 테이블(V2~V99 구간).
-- 근거: docs/static/06_데이터모델_및_무결성.md §2.1.
-- 신원과 인증 수단을 분리한다(인증 정보는 social_account 소유, 인증 PR에서 추가).
-- 익명 서비스라 저장하는 개인정보가 없어 컬럼이 적은 것이 정상이다.
-- updated_at 없음: member는 갱신 대상 속성이 없다.
-- status 없음: 삭제 판정 기준은 deleted_at 하나다.
CREATE TABLE core.member (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

-- 활성 회원 조회가 기본 경로이므로 부분 인덱스로 삭제분을 제외한다.
CREATE INDEX ix_member_active
    ON core.member (id)
    WHERE deleted_at IS NULL;
