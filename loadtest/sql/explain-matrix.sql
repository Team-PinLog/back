-- 주요 읽기 경로 4종 EXPLAIN 매트릭스 (S15P11A705-284).
--
-- 같은 파일을 두 규모(pinlog=천만 · pinlog_base=117k 재구성)에 돌려 대조한다:
--   docker exec -i back-postgres-1 psql -U pinlog -d pinlog       -v ON_ERROR_STOP=1 -f explain-matrix.sql
--   docker exec -i back-postgres-1 psql -U pinlog -d pinlog_base  -v ON_ERROR_STOP=1 -f explain-matrix.sql
--
-- 대상 id는 두 규모 모두에 존재하는 것으로 고정한다 — member 2792(레코드 697 · 컬렉션 59),
-- collection 11736(링크 20). 그래야 "데이터가 다른" 효과가 아니라 "표 크기가 다른" 효과만 남는다.
-- 쿼리 텍스트는 리포지터리의 JPQL이 만드는 SQL을 그대로 옮긴 것이다(각 항목에 출처 주석).
-- @SQLRestriction("deleted_at IS NULL")은 Hibernate가 WHERE에 덧붙이므로 여기서도 명시한다.

\timing on
SELECT current_database() AS "측정 DB",
       (SELECT count(*) FROM core.record) AS record_rows,
       (SELECT count(*) FROM core.collection) AS collection_rows;

------------------------------------------------------------------------------
\echo ''
\echo '######## [1] 커서 페이지네이션 — CollectionRepository.findFirstPageByMemberIdDesc / findPageByMemberIdAfterDesc'
------------------------------------------------------------------------------

\echo '--- [1a] 첫 페이지 (member 2792, size 20+1) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM core.collection c
WHERE c.deleted_at IS NULL AND c.member_id = 2792
ORDER BY c.created_at DESC, c.id DESC
LIMIT 21;

-- 깊은 페이지 커서(50번째 항목)를 먼저 뽑아 리터럴로 쓴다 — 서브쿼리를 EXPLAIN 안에 두면
-- 그 비용이 측정에 섞인다.
SELECT created_at AS cur_ts, id AS cur_id FROM core.collection
WHERE deleted_at IS NULL AND member_id = 2792
ORDER BY created_at DESC, id DESC
OFFSET 49 LIMIT 1 \gset

\echo '--- [1b] 깊은 페이지 (50번째 항목 이후, keyset) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM core.collection c
WHERE c.deleted_at IS NULL AND c.member_id = 2792
  AND (c.created_at < :'cur_ts' OR (c.created_at = :'cur_ts' AND c.id < :cur_id))
ORDER BY c.created_at DESC, c.id DESC
LIMIT 21;

------------------------------------------------------------------------------
\echo ''
\echo '######## [2] 컬렉션 상세 배치 5쿼리 — CollectionService.getDetail (collection 11736, 링크 20)'
------------------------------------------------------------------------------

\echo '--- [2a] Collection 단건 (findById) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM core.collection c WHERE c.deleted_at IS NULL AND c.id = 11736;

\echo '--- [2b] 링크 페이지 (findFirstPageByCollectionId, size 50+1) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM core.collection_record cr
WHERE cr.deleted_at IS NULL AND cr.collection_id = 11736
ORDER BY cr.created_at DESC, cr.id DESC
LIMIT 51;

-- 링크의 record id 목록을 뽑아 다음 세 배치의 입력으로 쓴다(서비스가 하는 그대로).
SELECT string_agg(record_id::text, ',') AS rec_ids FROM (
    SELECT record_id FROM core.collection_record
    WHERE deleted_at IS NULL AND collection_id = 11736
    ORDER BY created_at DESC, id DESC LIMIT 50) s \gset

\echo '--- [2c] Record 일괄 (findAllById) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM core.record r
WHERE r.deleted_at IS NULL AND r.id = ANY (string_to_array(:'rec_ids', ',')::bigint[]);

SELECT string_agg(DISTINCT place_id::text, ',') AS place_ids FROM core.record
WHERE deleted_at IS NULL AND id = ANY (string_to_array(:'rec_ids', ',')::bigint[]) \gset

\echo '--- [2d] Place 일괄 (findAllById) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM core.place p
WHERE p.id = ANY (string_to_array(:'place_ids', ',')::bigint[]);

\echo '--- [2e] Context 일괄 (findByRecordIdInOrderByOriginCreatedAtAscIdAsc) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM core.context ctx
WHERE ctx.deleted_at IS NULL
  AND ctx.record_id = ANY (string_to_array(:'rec_ids', ',')::bigint[])
ORDER BY ctx.origin_created_at ASC, ctx.id ASC;

------------------------------------------------------------------------------
\echo ''
\echo '######## [3] 지도 — RecordRepository.findMarkersWithinBounds (member 2792, 전국 뷰포트)'
------------------------------------------------------------------------------

EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, p.id, p.name, p.lat, p.lng
FROM core.record r JOIN core.place p ON p.id = r.place_id
WHERE r.deleted_at IS NULL AND r.member_id = 2792
  AND p.lat BETWEEN 33.0 AND 39.0
  AND p.lng BETWEEN 124.0 AND 132.0;

------------------------------------------------------------------------------
\echo ''
\echo '######## [4] 피드 후보 3채널 — FeedCandidateRepository (member 2792)'
------------------------------------------------------------------------------

\echo '--- [4a] 최신 채널 (RECENT_SQL, 현재 코드: COALESCE 정렬키) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id AS collection_id, c.member_id AS owner_id,
       COALESCE(c.published_at, c.created_at) AS published_at
FROM core.collection c JOIN core.member m ON m.id = c.member_id
WHERE c.deleted_at IS NULL AND c.is_published = true AND c.record_count > 0
  AND c.member_id <> 2792 AND m.deleted_at IS NULL
ORDER BY published_at DESC, c.id DESC
LIMIT 100;

\echo '--- [4a-대조] 같은 채널, COALESCE 없이 (원인 판정용 — 코드 아님) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id AS collection_id, c.member_id AS owner_id, c.published_at
FROM core.collection c JOIN core.member m ON m.id = c.member_id
WHERE c.deleted_at IS NULL AND c.is_published = true AND c.record_count > 0
  AND c.member_id <> 2792 AND m.deleted_at IS NULL
ORDER BY c.published_at DESC, c.id DESC
LIMIT 100;

\echo '--- [4b] 팔로우 채널 (FOLLOWED_SQL) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id AS collection_id, c.member_id AS owner_id,
       COALESCE(c.published_at, c.created_at) AS published_at
FROM core.follow f
JOIN core.collection c ON c.member_id = f.followee_member_id
JOIN core.member m ON m.id = c.member_id
WHERE f.follower_member_id = 2792 AND f.deleted_at IS NULL
  AND c.deleted_at IS NULL AND c.is_published = true AND c.record_count > 0
  AND c.member_id <> 2792 AND m.deleted_at IS NULL
ORDER BY published_at DESC, c.id DESC
LIMIT 80;

\echo '--- [4c] 무작위 채널 (SAMPLE_FROM_PIVOT_SQL, pivot 고정 42) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id AS collection_id, c.member_id AS owner_id,
       COALESCE(c.published_at, c.created_at) AS published_at
FROM core.collection c JOIN core.member m ON m.id = c.member_id
WHERE c.id >= 42
  AND c.deleted_at IS NULL AND c.is_published = true AND c.record_count > 0
  AND c.member_id <> 2792 AND m.deleted_at IS NULL
ORDER BY c.id
LIMIT 20;

\echo ''
\echo '######## 매트릭스 끝'
