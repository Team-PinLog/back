-- 부하용 회원 집합을 일괄 하드 삭제한다.
-- 입력: -v ids='1,2,3' (setup이 출력한 모든 회원 id — 공유 회원 포함, 쉼표 구분)
-- 1단계 teardown-test-member.sql의 골든 가드를 집합 버전으로 옮겼다.
\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE load_ids AS
SELECT unnest(string_to_array(:'ids', ','))::bigint AS id;

DO $guard$
BEGIN
  IF EXISTS (SELECT 1 FROM load_ids WHERE id <= 6) THEN
    RAISE EXCEPTION '골든 회원(id<=6)이 삭제 목록에 있다';
  END IF;
END $guard$;

DELETE FROM core.follow
WHERE follower_member_id IN (SELECT id FROM load_ids)
   OR followee_member_id IN (SELECT id FROM load_ids);

DELETE FROM core.collection_record
WHERE collection_id IN (SELECT c.id FROM core.collection c JOIN load_ids l ON l.id = c.member_id)
   OR record_id IN (SELECT r.id FROM core.record r JOIN load_ids l ON l.id = r.member_id);

DELETE FROM core.collection WHERE member_id IN (SELECT id FROM load_ids);
DELETE FROM core.context WHERE member_id IN (SELECT id FROM load_ids);
DELETE FROM core.record WHERE member_id IN (SELECT id FROM load_ids);
-- 부하용 장소(LT- 접두)는 부하 회원만 참조하므로 함께 지운다. 시드 장소는 접두어가 달라 안전.
DELETE FROM core.place
WHERE kakao_place_id LIKE 'LT-%'
  AND NOT EXISTS (SELECT 1 FROM core.record r WHERE r.place_id = core.place.id);
DELETE FROM core.social_account WHERE member_id IN (SELECT id FROM load_ids);
DELETE FROM core.member WHERE id IN (SELECT id FROM load_ids);

COMMIT;
