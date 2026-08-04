-- 대량 더미 생성기 (S15P11A705-283). 인덱스·쿼리 계획 검증용 볼륨을 로컬에 만든다.
--
-- 실행:
--   psql "$URL" -v ON_ERROR_STOP=1 -f seed-massive.sql                    # 회원 10만 → record 1천만
--   psql "$URL" -v ON_ERROR_STOP=1 -v members=1000 -f seed-massive.sql    # 회원 1천  → record 약 11만
--
-- **기존 데이터를 지우지 않는다.** 새 id를 현재 최대값 뒤에 이어 붙이므로 골든 셋
-- (member 1~6 · place 1~25 · record 1~25)과 기존 시드가 그대로 남는다. 하네스가 그 id를
-- 참조하기 때문이다(loadtest/k6/lib/config.js). 되돌리기는 teardown-massive.sql이 한다.
--
-- **`ai` 스키마와 `core.feed_event`는 건드리지 않는다.** AI 파트 소유다. 그래서 이 데이터로는
-- Keyword 집계·노출 패널티 쿼리를 검증할 수 없다(계획 검증 범위 밖 — S15P11A705-284에 기록).
--
-- **자원 한도 override를 얹은 상태로 적재하지 말 것.** 인덱스 재생성이 maintenance_work_mem을
-- 쓰므로 메모리를 조인 상태에서는 느려지거나 실패한다. 순서는 「기본 compose로 적재 →
-- override 얹고 측정」이다(loadtest/README.md).
--
-- 분포는 추정이 아니라 구간표다. 균등 분포로 만들면 회원당 행 수가 실측(p50 19 · p90 103 ·
-- max 722)과 어긋나 인덱스 선택도 추정이 현실과 달라진다. 그래서 세 자릿수가 갈라지도록 짠다.
--
--   구간     회원 비율   회원당 record   회원 10만일 때
--   monster   0.05%         20,000        50명 →  1,000,000
--   heavy     0.95%          4,000       950명 →  3,800,000
--   mid       9.00%            400     9,000명 →  3,600,000
--   light    40.00%             30    40,000명 →  1,200,000
--   tiny     50.00%              8    50,000명 →    400,000
--                                             계 → 10,000,000
--
-- `:members`만 바꾸면 형태를 유지하며 규모가 비례한다. 1,000명이면 약 11만 건이 되어 현재
-- 시드 규모(117,022)와 맞물리므로, 같은 분포로 두 규모를 비교할 수 있다.

\timing on

\if :{?members}
\else
  \set members 100000
\endif

\if :{?places}
\else
  \set places 200000
\endif

\echo ''
\echo '=== 대량 더미 생성 시작 ==='
\echo '회원 수 =' :members ' / 장소 수 =' :places

-- 회원당 record 상한(20,000)이 장소 수를 넘으면 uq_record_active(member_id, place_id)를
-- 만족시킬 장소가 부족하다. 미리 끊는다.
-- (DO 블록 안에서는 psql이 :vars를 치환하지 않으므로 \gset + \if로 검사한다.)
SELECT (:places < 20000) AS places_too_small,
       (:members < 20)   AS members_too_small \gset
\if :places_too_small
  \echo '중단: 장소 수가 회원당 record 상한(20000)보다 작다. uq_record_active를 만족시킬 수 없다'
  -- \quit은 종료 코드를 못 정하므로 예외로 끊는다(ON_ERROR_STOP=1 전제, 종료 코드 3).
  DO $fail$ BEGIN RAISE EXCEPTION '생성 조건 위반'; END $fail$;
\endif
\if :members_too_small
  \echo '중단: 회원 수가 너무 작다. 구간표가 무너진다(최소 20)'
  DO $fail$ BEGIN RAISE EXCEPTION '생성 조건 위반'; END $fail$;
\endif

-- 이어 붙일 기준점. 한 번만 읽어 고정한다 — 적재 중 max(id)가 움직이기 때문이다.
CREATE TEMP TABLE bench_base AS
SELECT (SELECT coalesce(max(id), 0) FROM core.member)            AS member_base,
       (SELECT coalesce(max(id), 0) FROM core.place)             AS place_base,
       (SELECT coalesce(max(id), 0) FROM core.record)            AS record_base,
       (SELECT coalesce(max(id), 0) FROM core.context)           AS context_base,
       (SELECT coalesce(max(id), 0) FROM core.collection)        AS collection_base,
       (SELECT coalesce(max(id), 0) FROM core.collection_record) AS colrec_base,
       (SELECT coalesce(max(id), 0) FROM core.follow)            AS follow_base,
       (SELECT coalesce(max(id), 0) FROM core.social_account)    AS social_base;

\echo '--- 이어 붙일 기준 id ---'
SELECT * FROM bench_base;

-- 회원별 계획. record_offset은 이 회원의 첫 record가 놓일 위치(0-based)다.
-- 컬렉션은 record 16건마다 1개, 각 컬렉션이 8건을 링크한다(record_count = 8, BD-20 일치).
CREATE TEMP TABLE bench_member AS
WITH band(ord, band, n_members, per_member) AS (
    VALUES (1, 'monster', greatest(1, (:members * 0.0005)::bigint), 20000),
           (2, 'heavy',   greatest(1, (:members * 0.0095)::bigint),  4000),
           (3, 'mid',     greatest(1, (:members * 0.0900)::bigint),   400),
           (4, 'light',   greatest(1, (:members * 0.4000)::bigint),    30),
           (5, 'tiny',    greatest(1, (:members * 0.5000)::bigint),     8)
),
seq AS (
    SELECT b.band,
           b.per_member,
           row_number() OVER (ORDER BY b.ord, gs) AS idx
    FROM band b, generate_series(1, b.n_members) AS gs
)
SELECT s.idx,
       s.band,
       s.per_member,
       ceil(s.per_member / 16.0)::bigint AS coll_count,
       (SELECT member_base FROM bench_base) + s.idx AS member_id,
       (sum(s.per_member) OVER (ORDER BY s.idx) - s.per_member)          AS record_offset,
       (sum(ceil(s.per_member / 16.0)::bigint) OVER (ORDER BY s.idx)
            - ceil(s.per_member / 16.0)::bigint)                        AS coll_offset
FROM seq s;

CREATE INDEX ON bench_member (idx);
ANALYZE bench_member;

\echo '--- 계획 요약 ---'
SELECT band,
       count(*)        AS 회원수,
       max(per_member) AS 회원당_record,
       sum(per_member) AS record_합계,
       sum(coll_count) AS collection_합계
FROM bench_member
GROUP BY band, per_member
ORDER BY per_member DESC;

SELECT count(*)        AS 총_회원,
       sum(per_member) AS 총_record,
       sum(coll_count) AS 총_collection
FROM bench_member;

BEGIN;

-- 비유니크 인덱스만 내렸다가 적재 후 되돌린다. 유니크 인덱스는 남긴다 — 생성기가 중복을
-- 만들면 그 자리에서 실패해야 하기 때문이다(속도보다 정합이 먼저다).
-- DDL을 손으로 적지 않고 pg_get_indexdef를 왕복시키므로 마이그레이션과 어긋날 수 없다.
CREATE TEMP TABLE bench_dropped_index AS
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'core'
  AND tablename IN ('member', 'social_account', 'place', 'record', 'context',
                    'collection', 'collection_record', 'follow')
  AND indexdef LIKE 'CREATE INDEX%';

\echo '--- 내릴 비유니크 인덱스 ---'
SELECT indexname FROM bench_dropped_index ORDER BY indexname;

DO $drop$
DECLARE r record;
BEGIN
    FOR r IN SELECT indexname FROM bench_dropped_index LOOP
        EXECUTE format('DROP INDEX core.%I', r.indexname);
    END LOOP;
END
$drop$;

-- 인덱스 빌드용. 적재는 자원 한도 override 없이 돌리는 것이 전제다(파일 머리 주석).
SET LOCAL maintenance_work_mem = '256MB';
SET LOCAL synchronous_commit = off;

\echo '--- member ---'
INSERT INTO core.member (id, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.member_id,
       '2024-06-01 00:00:00+09'::timestamptz + ((m.idx % 700) * interval '1 day')
FROM bench_member m;

\echo '--- social_account ---'
INSERT INTO core.social_account (id, member_id, provider, provider_user_id, email, created_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT social_base FROM bench_base) + m.idx,
       m.member_id,
       'GOOGLE',
       'BENCH-' || m.member_id,
       'bench-' || m.member_id || '@example.invalid',
       '2024-06-01 00:00:00+09'::timestamptz + ((m.idx % 700) * interval '1 day')
FROM bench_member m;

\echo '--- place ---'
INSERT INTO core.place (id, kakao_place_id, name, address, road_address, phone, place_url,
                        lat, lng, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT place_base FROM bench_base) + gs,
       'BENCH-' || ((SELECT place_base FROM bench_base) + gs),
       '벤치장소 ' || gs,
       '서울 강남구 테헤란로 ' || (gs % 500 + 1),
       '서울 강남구 테헤란로 ' || (gs % 500 + 1),
       NULL,
       NULL,
       33.0 + ((gs % 5000) / 1000.0),
       126.0 + ((gs % 3000) / 1000.0),
       now(),
       now()
FROM generate_series(1, :places) AS gs;

-- record: 회원당 per_member건. place는 (idx, k)로 회전시켜 회원 안에서 유일하다
-- (k가 1씩 늘고 per_member <= :places이므로 modulo가 겹치지 않는다) — uq_record_active 충족.
\echo '--- record (가장 오래 걸린다) ---'
INSERT INTO core.record (id, member_id, place_id, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT record_base FROM bench_base) + m.record_offset + k + 1,
       m.member_id,
       (SELECT place_base FROM bench_base) + ((m.idx * 7919 + k) % :places) + 1,
       '2024-06-01 00:00:00+09'::timestamptz
           + (((m.record_offset + k) % 730) * interval '1 day')
           + (((m.record_offset + k) % 1440) * interval '1 minute'),
       now()
FROM bench_member m, generate_series(0, m.per_member - 1) AS k;

-- context 1차: record 1건당 1건. id를 record와 1:1로 맞춰 계산을 단순하게 유지한다.
\echo '--- context 1차 (record 1:1) ---'
INSERT INTO core.context (id, record_id, member_id, body, origin_created_at, created_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT context_base FROM bench_base) + (r.id - (SELECT record_base FROM bench_base)),
       r.id,
       r.member_id,
       '벤치 맥락 ' || r.id || ' — 자리도 넓고 조용해서 오래 앉아 있기 좋았다.',
       r.created_at,
       r.created_at
FROM core.record r
WHERE r.id > (SELECT record_base FROM bench_base);

-- context 2차: 3건마다 1건 더. 회원당 맥락/기록 비율을 실측(1.33)에 맞춘다.
\echo '--- context 2차 (3건마다 1건) ---'
INSERT INTO core.context (id, record_id, member_id, body, origin_created_at, created_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT context_base FROM bench_base)
           + (SELECT sum(per_member) FROM bench_member)
           + ((r.id - (SELECT record_base FROM bench_base)) / 3),
       r.id,
       r.member_id,
       '벤치 재방문 ' || r.id || ' — 두 번째로 갔는데 이번엔 사람이 좀 많았다.',
       r.created_at + interval '30 day',
       r.created_at + interval '30 day'
FROM core.record r
WHERE r.id > (SELECT record_base FROM bench_base)
  AND (r.id - (SELECT record_base FROM bench_base)) % 3 = 0;

-- collection: 생성 즉시 발행이므로 is_published = true이고 published_at을 반드시 채운다
-- (V5 ck_collection_published_at). record_count는 아래 링크 수(8)와 일치시킨다(BD-20).
\echo '--- collection ---'
INSERT INTO core.collection (id, member_id, title, is_published, published_at,
                             record_count, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT collection_base FROM bench_base) + m.coll_offset + j + 1,
       m.member_id,
       '벤치 컬렉션 ' || m.idx || '-' || (j + 1),
       true,
       '2024-06-01 00:00:00+09'::timestamptz + (((m.coll_offset + j) % 730) * interval '1 day'),
       8,
       '2024-06-01 00:00:00+09'::timestamptz + (((m.coll_offset + j) % 730) * interval '1 day'),
       now()
FROM bench_member m, generate_series(0, m.coll_count - 1) AS j;

-- collection_record: 컬렉션당 8건. (j*8 + i) mod per_member라 컬렉션 안에서 유일하다
-- (per_member >= 8이 모든 구간에서 성립) — uq_colrec_active 충족.
\echo '--- collection_record ---'
INSERT INTO core.collection_record (id, collection_id, record_id, created_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT colrec_base FROM bench_base) + (m.coll_offset + j) * 8 + i + 1,
       (SELECT collection_base FROM bench_base) + m.coll_offset + j + 1,
       (SELECT record_base FROM bench_base) + m.record_offset
           + ((j * 8 + i) % m.per_member) + 1,
       '2024-06-01 00:00:00+09'::timestamptz + (((m.coll_offset + j) % 730) * interval '1 day')
FROM bench_member m,
     generate_series(0, m.coll_count - 1) AS j,
     generate_series(0, 7) AS i;

-- follow: 회원당 13명. 오프셋이 1 이상 :members 미만이라 자기 자신이 나오지 않는다
-- (ck_follow_self 충족). (followee, follower) 쌍도 겹치지 않는다(uq_follow_active).
\echo '--- follow ---'
INSERT INTO core.follow (id, followee_member_id, follower_member_id, display_name, created_at)
OVERRIDING SYSTEM VALUE
SELECT (SELECT follow_base FROM bench_base) + (m.idx - 1) * 13 + d,
       (SELECT member_base FROM bench_base) + (((m.idx + d - 1) % :members) + 1),
       m.member_id,
       NULL,
       '2024-06-01 00:00:00+09'::timestamptz + ((m.idx % 700) * interval '1 day')
FROM bench_member m, generate_series(1, 13) AS d;

-- 내려둔 인덱스를 원래 DDL 그대로 되돌린다.
\echo '--- 인덱스 재생성 ---'
DO $recreate$
DECLARE r record;
BEGIN
    FOR r IN SELECT indexname, indexdef FROM bench_dropped_index ORDER BY indexname LOOP
        RAISE NOTICE '  %', r.indexname;
        EXECUTE r.indexdef;
    END LOOP;
END
$recreate$;

-- identity 시퀀스를 실제 최대값 뒤로 옮긴다. 이게 없으면 이후 애플리케이션 INSERT가
-- 이미 쓴 id를 다시 발급해 PK 충돌로 실패한다.
\echo '--- identity 시퀀스 재정렬 ---'
DO $reseq$
DECLARE
    t text;
    n bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['member', 'social_account', 'place', 'record', 'context',
                             'collection', 'collection_record', 'follow'] LOOP
        EXECUTE format('SELECT coalesce(max(id), 0) + 1 FROM core.%I', t) INTO n;
        EXECUTE format('ALTER TABLE core.%I ALTER COLUMN id RESTART WITH %s', t, n);
        RAISE NOTICE '  core.% RESTART WITH %', t, n;
    END LOOP;
END
$reseq$;

COMMIT;

-- 통계를 갱신하지 않으면 플래너가 옛 카디널리티로 계획을 세워, 측정값이 규모를 반영하지 않는다.
\echo '--- ANALYZE ---'
ANALYZE core.member;
ANALYZE core.social_account;
ANALYZE core.place;
ANALYZE core.record;
ANALYZE core.context;
ANALYZE core.collection;
ANALYZE core.collection_record;
ANALYZE core.follow;

\echo ''
\echo '=== 적재 완료. verify-massive.sql로 확인할 것 ==='
