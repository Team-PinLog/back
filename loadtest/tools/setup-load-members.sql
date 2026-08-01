-- 부하용 회원 풀을 한 트랜잭션에 만든다.
-- 입력: -v member_count=N (처리량 시나리오용 회원 수 = 프로파일 최대 VU)
-- 출력(-t -A 기준):
--   shared:<id>   경합 시나리오가 공유하는 회원
--   record:<id>   그 회원의 공유 Record (경합 대상)
--   <id> × N      처리량 시나리오용 회원들
--
-- 모든 회원에 소셜 계정을 함께 만든다 — "소셜 계정 없는 활성 회원"은 API로 도달 불가능한
-- 상태라 me/summary가 계약대로 500을 낸다(1단계에서 확인).
-- 공유 Record에는 활성 Context 2개를 미리 넣는다 — 경합 시나리오의 삭제가 BD-11의
-- "마지막 Context 409"에 즉시 걸리지 않고 추가/삭제를 반복할 수 있게.
\set QUIET on

WITH shared_member AS (
  INSERT INTO core.member DEFAULT VALUES RETURNING id
), shared_account AS (
  INSERT INTO core.social_account (member_id, provider, provider_user_id, email)
  SELECT id, 'GOOGLE', 'LT-SHARED-' || id, 'loadtest-shared-' || id || '@example.invalid'
  FROM shared_member RETURNING member_id
), shared_place AS (
  INSERT INTO core.place (kakao_place_id, name, address, road_address, lat, lng)
  SELECT 'LT-SHARED-' || id, '경합 검증 장소', '서울 강남구 테헤란로 1', '서울 강남구 테헤란로 1', 37.5, 127.03
  FROM shared_member RETURNING id
), shared_record AS (
  INSERT INTO core.record (member_id, place_id)
  SELECT m.id, p.id FROM shared_member m, shared_place p RETURNING id, member_id
), shared_contexts AS (
  INSERT INTO core.context (record_id, member_id, body, origin_created_at)
  SELECT r.id, r.member_id, '경합 기준 맥락 ' || g, now()
  FROM shared_record r, generate_series(1, 2) g RETURNING id
), pool AS (
  -- id가 GENERATED ALWAYS라 넣을 수 있는 열이 created_at뿐이다. "INSERT ... SELECT FROM
  -- generate_series"처럼 열 없이 쓰면 열 수 불일치로 죽는다 — default를 쓰려면 열 하나를
  -- 명시하고 그 값을 SELECT해야 N행이 나온다.
  INSERT INTO core.member (created_at)
  SELECT now() FROM generate_series(1, :member_count)
  RETURNING id
), pool_accounts AS (
  INSERT INTO core.social_account (member_id, provider, provider_user_id, email)
  SELECT id, 'GOOGLE', 'LT-' || id, 'loadtest-' || id || '@example.invalid'
  FROM pool RETURNING member_id
)
SELECT 'shared:' || id FROM shared_member
UNION ALL
SELECT 'record:' || id FROM shared_record
UNION ALL
SELECT id::text FROM pool ORDER BY 1;
