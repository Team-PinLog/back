-- 검증 전용 회원 1명을 만든다. id는 시퀀스가 준다 —
-- core.member.id가 generated always as identity라 예약 id는 OVERRIDING SYSTEM VALUE가 필요하고
-- 시퀀스와 어긋날 위험이 있다.
--
-- 소셜 계정도 함께 만든다. 가입이 회원과 소셜 계정을 한 트랜잭션에서 만들므로
-- "활성 회원인데 소셜 계정이 없다"는 API로 도달 불가능한 상태이고,
-- MemberSummaryService는 그 상태를 계약대로 500으로 던진다. 회원 행만 만들면
-- GET /v1/me/summary 검사가 하네스 자신의 합성 상태 때문에 깨진다.
-- provider_user_id의 LT- 접두어는 실제 공급자 id(숫자)와 절대 겹치지 않게 한다.
--
-- 출력: member_id 한 열 한 행. run.sh이 -t -A로 읽어 k6에 넘긴다.
-- QUIET가 없으면 -t -A로도 명령 태그("INSERT 0 1")가 두 번째 줄로 나와서
-- 받은 값이 숫자 검사에 걸린다. 호출자에게 head -1을 요구하지 않고 여기서 막는다.
\set QUIET on
WITH new_member AS (
  INSERT INTO core.member DEFAULT VALUES
  RETURNING id
), new_account AS (
  INSERT INTO core.social_account (member_id, provider, provider_user_id, email)
  SELECT id, 'GOOGLE', 'LT-' || id, 'loadtest-' || id || '@example.invalid'
  FROM new_member
  RETURNING member_id
)
SELECT id AS member_id FROM new_member;
