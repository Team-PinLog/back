-- seed-massive.sql이 넣은 행만 되돌린다(S15P11A705-283).
--
-- 실행: psql "$URL" -v ON_ERROR_STOP=1 -f teardown-massive.sql
--
-- 기준점을 저장해 두지 않고 **표식으로 찾는다.** 벤치 회원은 social_account의
-- provider_user_id가 'BENCH-'로 시작하고, 벤치 장소는 kakao_place_id가 'BENCH-'로 시작한다.
-- 실행 중에 끊겨 기준점 파일이 없어도 되돌릴 수 있어야 하기 때문이다.
--
-- **천만 건이면 이 스크립트가 오래 걸린다.** DELETE는 인덱스를 전부 갱신하고 죽은 행을 남긴다.
-- 로컬을 통째로 초기화할 생각이면 볼륨을 지우고 시드를 다시 넣는 편이 훨씬 빠르다:
--   docker compose down -v && docker compose up -d   (그 뒤 앱 기동으로 Flyway 재적용 + 시드 재적재)
-- 이 스크립트는 기존 시드를 살려 두고 벤치 분량만 걷어내야 할 때 쓴다.

\timing on

\echo ''
\echo '=== 벤치 분량 되돌리기 시작 ==='

CREATE TEMP TABLE bench_member_id AS
SELECT DISTINCT sa.member_id
FROM core.social_account sa
WHERE sa.provider_user_id LIKE 'BENCH-%';

CREATE INDEX ON bench_member_id (member_id);
ANALYZE bench_member_id;

\echo '--- 되돌릴 대상 ---'
SELECT (SELECT count(*) FROM bench_member_id)                                  AS 벤치_회원,
       (SELECT count(*) FROM core.record r
          WHERE r.member_id IN (SELECT member_id FROM bench_member_id))        AS 벤치_record,
       (SELECT count(*) FROM core.place WHERE kakao_place_id LIKE 'BENCH-%')   AS 벤치_place;

DO $guard$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM bench_member_id) THEN
        RAISE EXCEPTION '벤치 회원이 없다. 되돌릴 것이 없으므로 중단한다';
    END IF;
END
$guard$;

BEGIN;

\echo '--- collection_record ---'
DELETE FROM core.collection_record cr
WHERE cr.collection_id IN (
    SELECT c.id FROM core.collection c
    WHERE c.member_id IN (SELECT member_id FROM bench_member_id));

\echo '--- collection ---'
DELETE FROM core.collection c
WHERE c.member_id IN (SELECT member_id FROM bench_member_id);

\echo '--- context ---'
DELETE FROM core.context ctx
WHERE ctx.member_id IN (SELECT member_id FROM bench_member_id);

\echo '--- record ---'
DELETE FROM core.record r
WHERE r.member_id IN (SELECT member_id FROM bench_member_id);

-- 양방향 모두 지운다. 벤치 회원이 팔로우한 쪽과 팔로우당한 쪽이 다 벤치 회원이지만,
-- 한쪽만 지우면 남은 행이 사라진 회원을 참조해 FK가 깨진다.
\echo '--- follow ---'
DELETE FROM core.follow f
WHERE f.follower_member_id IN (SELECT member_id FROM bench_member_id)
   OR f.followee_member_id IN (SELECT member_id FROM bench_member_id);

\echo '--- social_account ---'
DELETE FROM core.social_account sa
WHERE sa.member_id IN (SELECT member_id FROM bench_member_id);

\echo '--- member ---'
DELETE FROM core.member m
WHERE m.id IN (SELECT member_id FROM bench_member_id);

-- 장소는 마지막이다. 벤치 record가 남아 있으면 FK가 막는다 — 순서가 틀리면 여기서 실패한다.
\echo '--- place ---'
DELETE FROM core.place p
WHERE p.kakao_place_id LIKE 'BENCH-%';

COMMIT;

\echo '--- 남은 규모 ---'
SELECT 'member' AS 테이블, count(*) AS 행 FROM core.member
UNION ALL SELECT 'place', count(*) FROM core.place
UNION ALL SELECT 'record', count(*) FROM core.record
UNION ALL SELECT 'context', count(*) FROM core.context
UNION ALL SELECT 'collection', count(*) FROM core.collection
UNION ALL SELECT 'collection_record', count(*) FROM core.collection_record
UNION ALL SELECT 'follow', count(*) FROM core.follow;

\echo '--- VACUUM (죽은 행 회수) ---'
VACUUM (ANALYZE) core.record;
VACUUM (ANALYZE) core.context;
VACUUM (ANALYZE) core.collection_record;
VACUUM (ANALYZE) core.collection;
VACUUM (ANALYZE) core.follow;
VACUUM (ANALYZE) core.place;
VACUUM (ANALYZE) core.social_account;
VACUUM (ANALYZE) core.member;

\echo ''
\echo '=== 되돌리기 완료 ==='
