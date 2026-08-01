-- 전용 테스트 회원과 그 회원이 만든 것만 하드 삭제한다.
-- 소프트 삭제 도메인이라 API로는 행이 사라지지 않으므로 SQL이어야 한다.
--
-- 사용: psql -v member_id=<id> -f teardown-test-member.sql
-- 삭제 순서는 FK 역순이다. core.place는 여러 회원이 공유하므로(대량 시드가 1:N을 만든다) 손대지 않는다.
\set ON_ERROR_STOP on

BEGIN;

-- 골든 id를 지우려는 시도를 막는다. 골든은 member 1~6이다.
--
-- psql은 달러 인용 문자열 안에서 :member_id를 치환하지 않는다(문자열 리터럴이라 건드리지
-- 않는다). DO $$ ... :member_id ... $$로 쓰면 `syntax error at or near ":"`가 난다.
-- 그래서 값을 먼저 커스텀 설정값으로 넘기고 블록 안에서는 current_setting으로 읽는다.
SET pinlog.target_member_id = :'member_id';

DO $guard$
BEGIN
  IF current_setting('pinlog.target_member_id')::bigint <= 6 THEN
    RAISE EXCEPTION '골든 회원(id<=6)은 지울 수 없다: %',
      current_setting('pinlog.target_member_id');
  END IF;
END $guard$;

DELETE FROM core.follow
WHERE follower_member_id = :member_id OR followee_member_id = :member_id;

DELETE FROM core.collection_record
WHERE collection_id IN (SELECT id FROM core.collection WHERE member_id = :member_id)
   OR record_id IN (SELECT id FROM core.record WHERE member_id = :member_id);

DELETE FROM core.collection WHERE member_id = :member_id;

DELETE FROM core.context WHERE member_id = :member_id;

DELETE FROM core.record WHERE member_id = :member_id;

-- setup은 social_account를 만들지 않지만, 이 행이 있으면 member 삭제가 FK로 막혀
-- 테스트 회원이 조용히 누적된다. 오늘은 0건 삭제 no-op이고, 하네스가 언젠가
-- 소셜 연결을 만들게 되어도 여기서 막히지 않는다.
DELETE FROM core.social_account WHERE member_id = :member_id;

DELETE FROM core.member WHERE id = :member_id;

COMMIT;
