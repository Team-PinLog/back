-- seed-massive.sql 적재 결과 검증 (S15P11A705-283). 티켓 완료 조건을 PASS/FAIL로 판정한다.
--
-- 실행:
--   psql "$URL" -v ON_ERROR_STOP=1 -f verify-massive.sql                  # 기본: record 1천만 기준
--   psql "$URL" -v ON_ERROR_STOP=1 -v min_records=100000 -f verify-massive.sql   # 축소 실행 검증
--
-- 실패한 판정이 하나라도 있으면 종료 코드 1로 끝난다(run-massive.sh가 이 코드로 게이트한다).
-- 검사는 티켓의 완료 조건 순서를 따르고, 마지막에 정합 검사 3종을 더한다 — 생성기가
-- 유니크 제약은 DB가 지켜주지만 record_count(BD-20)·published_at(BD-33) 같은
-- 값 정합은 생성기 자신이 맞춰야 하기 때문이다.

\if :{?min_records}
\else
  \set min_records 10000000
\endif
\if :{?min_members}
\else
  \set min_members 100000
\endif

\echo ''
\echo '=== 적재 검증 (기준: record' :min_records '· member' :min_members ') ==='
\echo ''

CREATE TEMP TABLE verify_result (ord int, name text, pass boolean, detail text);

-- [1] 행 수
INSERT INTO verify_result
SELECT 1, '행 수: record >= 기준',
       (SELECT count(*) FROM core.record) >= :min_records,
       'record=' || (SELECT count(*) FROM core.record);

INSERT INTO verify_result
SELECT 2, '행 수: member >= 기준',
       (SELECT count(*) FROM core.member) >= :min_members,
       'member=' || (SELECT count(*) FROM core.member);

-- [2] 분포: p50·p90·max가 서로 다른 자릿수 + 1만 건 이상 회원 존재
WITH per AS (
    SELECT count(*) AS n FROM core.record WHERE deleted_at IS NULL GROUP BY member_id
),
q AS (
    SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY n) AS p50,
           percentile_disc(0.9) WITHIN GROUP (ORDER BY n) AS p90,
           max(n) AS mx
    FROM per
)
INSERT INTO verify_result
SELECT 3, '분포: p50·p90·max 자릿수가 서로 다름',
       length(p50::text) <> length(p90::text) AND length(p90::text) <> length(mx::text),
       format('p50=%s p90=%s max=%s', p50, p90, mx)
FROM q;

INSERT INTO verify_result
SELECT 4, '분포: 1만 건 이상 회원 존재',
       EXISTS (SELECT 1 FROM core.record WHERE deleted_at IS NULL
               GROUP BY member_id HAVING count(*) >= 10000),
       (SELECT count(*)::text || '명' FROM (
           SELECT 1 FROM core.record WHERE deleted_at IS NULL
           GROUP BY member_id HAVING count(*) >= 10000) s);

-- [3] 골든 셋 보존 (member 1~6 · place 1~25 · record 1~25)
INSERT INTO verify_result
SELECT 5, '골든 셋: member 1~6 · place 1~25 · record 1~25 존재',
       (SELECT count(*) FROM core.member WHERE id BETWEEN 1 AND 6) = 6
       AND (SELECT count(*) FROM core.place WHERE id BETWEEN 1 AND 25) = 25
       AND (SELECT count(*) FROM core.record WHERE id BETWEEN 1 AND 25) = 25,
       format('member=%s/6 place=%s/25 record=%s/25',
              (SELECT count(*) FROM core.member WHERE id BETWEEN 1 AND 6),
              (SELECT count(*) FROM core.place WHERE id BETWEEN 1 AND 25),
              (SELECT count(*) FROM core.record WHERE id BETWEEN 1 AND 25));

-- [4] 플래너 통계 갱신 (last_analyze가 이 세션 언저리인지까지는 묻지 않는다 —
--     seed-massive가 마지막에 ANALYZE를 돌리므로 존재 여부가 곧 갱신 여부다)
INSERT INTO verify_result
SELECT 6, '통계: 대상 테이블 pg_stats 존재',
       (SELECT count(DISTINCT tablename) FROM pg_stats
        WHERE schemaname = 'core'
          AND tablename IN ('member','place','record','context','collection',
                            'collection_record','follow')) = 7,
       (SELECT count(DISTINCT tablename)::text || '/7 테이블' FROM pg_stats
        WHERE schemaname = 'core'
          AND tablename IN ('member','place','record','context','collection',
                            'collection_record','follow'));

-- 정합 검사는 **벤치 생성분에 한정한다.** 기존 시드에는 알려진 기준선 위반이 있고
-- (빈 Collection 137건 등, BI-33에서 시드 생성기 산물로 삼분류 완료), 그걸 여기서 다시
-- 세면 생성기 결함과 구분되지 않는다. 전역 기준선은 verify-invariants.sql 소관이다.
CREATE TEMP TABLE bench_member_id AS
SELECT DISTINCT member_id FROM core.social_account
WHERE provider_user_id LIKE 'BENCH-%';
CREATE INDEX ON bench_member_id (member_id);
ANALYZE bench_member_id;

-- [5] 정합: record_count = 활성 링크 수 (BD-20). 벤치 컬렉션은 삭제 행이 없으므로 전수 일치해야 한다.
INSERT INTO verify_result
SELECT 7, '정합(벤치): collection.record_count = 활성 링크 수 (BD-20)',
       NOT EXISTS (
           SELECT 1
           FROM core.collection c
           LEFT JOIN LATERAL (
               SELECT count(*) AS links FROM core.collection_record cr
               WHERE cr.collection_id = c.id AND cr.deleted_at IS NULL) l ON true
           WHERE c.deleted_at IS NULL
             AND c.member_id IN (SELECT member_id FROM bench_member_id)
             AND c.record_count <> l.links),
       COALESCE((SELECT '불일치 ' || count(*) || '건' FROM (
           SELECT 1
           FROM core.collection c
           LEFT JOIN LATERAL (
               SELECT count(*) AS links FROM core.collection_record cr
               WHERE cr.collection_id = c.id AND cr.deleted_at IS NULL) l ON true
           WHERE c.deleted_at IS NULL
             AND c.member_id IN (SELECT member_id FROM bench_member_id)
             AND c.record_count <> l.links) s
           HAVING count(*) > 0), '전수 일치');

-- [6] 정합: 발행됐는데 published_at이 없는 행 없음 (BD-33 / V5 CHECK와 같은 조건)
INSERT INTO verify_result
SELECT 8, '정합: is_published → published_at NOT NULL (BD-33)',
       NOT EXISTS (SELECT 1 FROM core.collection
                   WHERE is_published AND published_at IS NULL),
       COALESCE((SELECT '위반 ' || count(*) || '건' FROM core.collection
                 WHERE is_published AND published_at IS NULL
                 HAVING count(*) > 0), '위반 없음');

-- [7] 정합: Collection은 활성 Record 최소 1건 (BD-11). 벤치 컬렉션은 8건씩 링크했으므로 0이면 생성기 결함.
--     (기존 시드의 빈 Collection 137건은 알려진 기준선이라 여기서 세지 않는다 — BI-33.)
INSERT INTO verify_result
SELECT 9, '정합(벤치): 빈 활성 Collection 없음 (BD-11)',
       NOT EXISTS (
           SELECT 1 FROM core.collection c
           WHERE c.deleted_at IS NULL
             AND c.member_id IN (SELECT member_id FROM bench_member_id)
             AND NOT EXISTS (
                 SELECT 1 FROM core.collection_record cr
                 WHERE cr.collection_id = c.id AND cr.deleted_at IS NULL)),
       COALESCE((SELECT '빈 컬렉션 ' || count(*) || '건' FROM (
           SELECT 1 FROM core.collection c
           WHERE c.deleted_at IS NULL
             AND c.member_id IN (SELECT member_id FROM bench_member_id)
             AND NOT EXISTS (
                 SELECT 1 FROM core.collection_record cr
                 WHERE cr.collection_id = c.id AND cr.deleted_at IS NULL)) s
           HAVING count(*) > 0), '없음');

-- [8] 정합: 벤치 회원은 전원 social_account 동반 (없으면 me/summary가 계약대로 500 — BI-33에서 확인된 함정)
INSERT INTO verify_result
SELECT 10, '정합: 벤치 회원 전원 social_account 보유',
       NOT EXISTS (
           SELECT 1 FROM core.social_account sa
           WHERE sa.provider_user_id LIKE 'BENCH-%'
           GROUP BY sa.member_id HAVING count(*) <> 1)
       AND (SELECT count(*) FROM core.social_account WHERE provider_user_id LIKE 'BENCH-%')
           = (SELECT count(*) FROM core.member m
              WHERE EXISTS (SELECT 1 FROM core.social_account sa
                            WHERE sa.member_id = m.id AND sa.provider_user_id LIKE 'BENCH-%')),
       (SELECT 'BENCH 계정 ' || count(*) || '건'
        FROM core.social_account WHERE provider_user_id LIKE 'BENCH-%');

-- 결과 출력
\echo '--- 판정 ---'
SELECT CASE WHEN pass THEN 'PASS' ELSE 'FAIL' END AS 판정, name AS 검사, detail AS 상세
FROM verify_result ORDER BY ord;

SELECT (count(*) FILTER (WHERE NOT pass)) AS fail_count FROM verify_result \gset

\if :{?fail_count}
\endif

SELECT :fail_count = 0 AS all_pass \gset
\if :all_pass
  \echo ''
  \echo '=== 전부 PASS ==='
\else
  \echo ''
  \echo '=== FAIL' :fail_count '건 — 위 표를 볼 것 ==='
  -- psql의 \quit은 종료 코드를 못 정한다. ON_ERROR_STOP=1 전제에서 예외를 던져
  -- 0이 아닌 코드(3)로 끝낸다 — run-massive.sh가 이 코드로 게이트한다.
  DO $fail$ BEGIN RAISE EXCEPTION '검증 실패'; END $fail$;
\endif
