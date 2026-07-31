-- k6가 이번 실행에 만진 행만 지목해 검증한다.
-- 입력: -v touched='<touched.json의 touched 배열>'
--
-- 전역 훑기(verify-invariants.sql)와 나누는 이유는 진단력이다. 여기서 깨지면 방금 돌린
-- 시나리오가 범인이고, 어느 행인지 바로 나온다.
\set ON_ERROR_STOP on

CREATE TEMP TABLE touched AS
SELECT *
FROM json_to_recordset(:'touched'::json) AS t(kind text, id bigint, note text);

CREATE TEMP TABLE id_findings (
  check_name text,
  table_name text,
  row_id     bigint,
  expected   text,
  actual     text,
  note       text
);

-- 1. 만졌다고 기록한 행이 실제로 DB에 있는가.
INSERT INTO id_findings
SELECT '지목 행 존재', 'core.record', t.id, '행 있음', '행 없음', t.note
FROM touched t
WHERE t.kind = 'record'
  AND NOT EXISTS (SELECT 1 FROM core.record r WHERE r.id = t.id);

INSERT INTO id_findings
SELECT '지목 행 존재', 'core.context', t.id, '행 있음', '행 없음', t.note
FROM touched t
WHERE t.kind = 'context'
  AND NOT EXISTS (SELECT 1 FROM core.context c WHERE c.id = t.id);

INSERT INTO id_findings
SELECT '지목 행 존재', 'core.collection', t.id, '행 있음', '행 없음', t.note
FROM touched t
WHERE t.kind = 'collection'
  AND NOT EXISTS (SELECT 1 FROM core.collection c WHERE c.id = t.id);

INSERT INTO id_findings
SELECT '지목 행 존재', 'core.follow', t.id, '행 있음', '행 없음', t.note
FROM touched t
WHERE t.kind = 'follow'
  AND NOT EXISTS (SELECT 1 FROM core.follow f WHERE f.id = t.id);

-- 2. BD-20: 만진 Collection의 record_count가 실제 활성 연결 수와 같은가.
--    응답 본문에는 드러나지 않는 비정규화 컬럼이다.
INSERT INTO id_findings
SELECT
  'BD-20 record_count 일치',
  'core.collection',
  c.id,
  c.record_count::text,
  count(cr.record_id)::text,
  '활성 collection_record 수와 record_count가 다르다'
FROM core.collection c
JOIN (SELECT DISTINCT id FROM touched WHERE kind IN ('collection', 'collection_record')) t
  ON t.id = c.id
LEFT JOIN core.collection_record cr
  ON cr.collection_id = c.id AND cr.deleted_at IS NULL
GROUP BY c.id, c.record_count
HAVING c.record_count <> count(cr.record_id);

-- 3. BD-08: 소프트 삭제는 행을 남긴다. 삭제했다고 기록한 Context가 하드 삭제되지 않았는가.
INSERT INTO id_findings
SELECT
  'BD-08 소프트 삭제 유지',
  'core.context',
  t.id,
  'deleted_at 있음',
  'deleted_at 비어 있음',
  t.note
FROM touched t
JOIN core.context c ON c.id = t.id
WHERE t.kind = 'context'
  AND t.note LIKE '%소프트 삭제%'
  AND c.deleted_at IS NULL;

-- 4. BD-25: 만진 Context의 origin_created_at이 자기 created_at보다 미래일 수 없다.
--    첫 생성은 같은 값, 교체는 구 값 승계이므로 항상 origin <= created다.
INSERT INTO id_findings
SELECT
  'BD-25 origin_created_at 승계',
  'core.context',
  c.id,
  'origin_created_at <= created_at',
  format('origin=%s created=%s', c.origin_created_at, c.created_at),
  t.note
FROM core.context c
JOIN touched t ON t.id = c.id AND t.kind = 'context'
WHERE c.origin_created_at > c.created_at;

-- 5. BD-11: force 삭제한 Record 아래 살아 있는 Context가 없는가.
INSERT INTO id_findings
SELECT
  'BD-11 force 연쇄',
  'core.context',
  c.id,
  '부모가 삭제되면 자식도 삭제',
  format('record %s는 삭제됐는데 context가 살아 있다', r.id),
  t.note
FROM touched t
JOIN core.record r ON r.id = t.id
JOIN core.context c ON c.record_id = r.id
WHERE t.kind = 'record'
  AND r.deleted_at IS NOT NULL
  AND c.deleted_at IS NULL;

-- 6. BD-33: 만진 Collection이 발행 상태면 published_at이 있어야 한다.
INSERT INTO id_findings
SELECT
  'BD-33 published_at 정합',
  'core.collection',
  c.id,
  'is_published면 published_at 있음',
  format('is_published=%s published_at=%s', c.is_published, c.published_at),
  t.note
FROM core.collection c
JOIN touched t ON t.id = c.id AND t.kind = 'collection'
WHERE c.is_published AND c.published_at IS NULL;

\echo '=== 지목 검증 결과 ==='
SELECT check_name, table_name, row_id, expected, actual, note
FROM id_findings
ORDER BY check_name, row_id;

\echo '=== 지목 검증 위반 건수 ==='
SELECT count(*) AS id_violations FROM id_findings;
