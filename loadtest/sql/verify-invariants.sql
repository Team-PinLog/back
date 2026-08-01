-- DB 전역 불변식. id 연결 없이 전체를 훑는다.
-- 기존 117k 데이터에 이미 숨어 있는 불정합까지 잡는 자리다.
--
-- owner 열이 'ai'인 항목은 AI 파트(이정헌) 소유라 이 작업에서 고치지 않고 보고만 한다.
\set ON_ERROR_STOP on

CREATE TEMP TABLE violations (
  owner      text,
  check_name text,
  table_name text,
  row_id     bigint,
  expected   text,
  actual     text
);

-- BD-20. 선택적 비정규화: record_count는 활성 연결 수와 같아야 한다.
INSERT INTO violations
SELECT 'back', 'BD-20 record_count', 'core.collection', c.id,
       c.record_count::text, count(cr.record_id)::text
FROM core.collection c
LEFT JOIN core.collection_record cr
  ON cr.collection_id = c.id AND cr.deleted_at IS NULL
WHERE c.deleted_at IS NULL
GROUP BY c.id, c.record_count
HAVING c.record_count <> count(cr.record_id);

-- BD-11. 최소 보유: 활성 Record는 활성 Context를 하나 이상 가진다.
INSERT INTO violations
SELECT 'back', 'BD-11 Record 최소 보유', 'core.record', r.id, '활성 context >= 1', '0'
FROM core.record r
WHERE r.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM core.context c WHERE c.record_id = r.id AND c.deleted_at IS NULL
  );

-- BD-11. 최소 보유: 활성 Collection은 활성 연결을 하나 이상 가진다.
INSERT INTO violations
SELECT 'back', 'BD-11 Collection 최소 보유', 'core.collection', c.id,
       '활성 collection_record >= 1', '0'
FROM core.collection c
WHERE c.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM core.collection_record cr
    WHERE cr.collection_id = c.id AND cr.deleted_at IS NULL
  );

-- BI-12. 삭제 파급: 삭제된 부모 아래 살아 있는 자식이 없다.
INSERT INTO violations
SELECT 'back', 'BI-12 삭제된 Record의 살아있는 Context', 'core.context', c.id,
       '부모 삭제 시 함께 삭제', format('record %s는 삭제됨', r.id)
FROM core.context c
JOIN core.record r ON r.id = c.record_id
WHERE r.deleted_at IS NOT NULL AND c.deleted_at IS NULL;

INSERT INTO violations
SELECT 'back', 'BI-12 삭제된 Collection의 살아있는 연결', 'core.collection_record', cr.id,
       '부모 삭제 시 함께 삭제', format('collection %s는 삭제됨', c.id)
FROM core.collection_record cr
JOIN core.collection c ON c.id = cr.collection_id
WHERE c.deleted_at IS NOT NULL AND cr.deleted_at IS NULL;

INSERT INTO violations
SELECT 'back', 'BI-12 삭제된 Record를 가리키는 살아있는 연결', 'core.collection_record', cr.id,
       '참조 대상 삭제 시 함께 삭제', format('record %s는 삭제됨', r.id)
FROM core.collection_record cr
JOIN core.record r ON r.id = cr.record_id
WHERE r.deleted_at IS NOT NULL AND cr.deleted_at IS NULL;

INSERT INTO violations
SELECT 'back', 'BI-12 탈퇴 회원의 살아있는 Record', 'core.record', r.id,
       '회원 탈퇴 시 함께 삭제', format('member %s는 탈퇴됨', m.id)
FROM core.record r
JOIN core.member m ON m.id = r.member_id
WHERE m.deleted_at IS NOT NULL AND r.deleted_at IS NULL;

-- BD-25. origin_created_at 승계.
-- 규칙: 첫 생성은 자신의 created_at, 교체는 구 Context의 origin_created_at 복사.
-- 따라서 항상 origin <= created다.
INSERT INTO violations
SELECT 'back', 'BD-25 origin이 created보다 미래', 'core.context', c.id,
       'origin_created_at <= created_at',
       format('origin=%s created=%s', c.origin_created_at, c.created_at)
FROM core.context c
WHERE c.origin_created_at > c.created_at;

-- BD-25. 승계 사슬: origin_created_at은 같은 Record의 어떤 Context created_at과 일치해야 한다
--        (그 값의 출처가 최초 Context의 created_at이므로).
INSERT INTO violations
SELECT 'back', 'BD-25 승계 사슬 끊김', 'core.context', c.id,
       '같은 record에 origin과 같은 created_at을 가진 context가 있음',
       format('origin=%s인데 대응 행 없음', c.origin_created_at)
FROM core.context c
WHERE NOT EXISTS (
  SELECT 1 FROM core.context o
  WHERE o.record_id = c.record_id AND o.created_at = c.origin_created_at
);

-- BD-33. published_at 불변식. DB CHECK(ck_collection_published_at)가 막지만
-- 마이그레이션 이전 데이터나 직접 UPDATE로 어긋날 수 있어 확인한다.
INSERT INTO violations
SELECT 'back', 'BD-33 published_at 정합', 'core.collection', c.id,
       'is_published면 published_at 있음',
       format('is_published=%s published_at=%s', c.is_published, c.published_at)
FROM core.collection c
WHERE c.is_published AND c.published_at IS NULL;

-- BD-08. 부분 유니크는 활성 행에만 걸린다. 활성 중복이 있으면 유니크가 무력화된 것이다.
INSERT INTO violations
SELECT 'back', 'BD-08 활성 Record 중복', 'core.record', min(r.id),
       '(member_id, place_id) 활성 1건', count(*)::text
FROM core.record r
WHERE r.deleted_at IS NULL
GROUP BY r.member_id, r.place_id
HAVING count(*) > 1;

INSERT INTO violations
SELECT 'back', 'BD-08 활성 연결 중복', 'core.collection_record', min(cr.id),
       '(collection_id, record_id) 활성 1건', count(*)::text
FROM core.collection_record cr
WHERE cr.deleted_at IS NULL
GROUP BY cr.collection_id, cr.record_id
HAVING count(*) > 1;

INSERT INTO violations
SELECT 'back', 'BD-08 활성 Follow 중복', 'core.follow', min(f.id),
       '(followee, follower) 활성 1건', count(*)::text
FROM core.follow f
WHERE f.deleted_at IS NULL
GROUP BY f.followee_member_id, f.follower_member_id
HAVING count(*) > 1;

-- BD-37. 삭제된 Context의 AI 파생물은 삭제 트랜잭션 안에서 무효화된다.
-- ai 스키마는 SELECT만 한다. 위반이 나와도 이 작업에서 고치지 않는다.
INSERT INTO violations
SELECT 'ai', 'BD-37 삭제 Context의 임베딩 잔존', 'ai.context_embedding', e.context_id,
       '삭제 시 함께 제거', 'context는 삭제됐는데 임베딩이 남아 있다'
FROM ai.context_embedding e
JOIN core.context c ON c.id = e.context_id
WHERE c.deleted_at IS NOT NULL;

INSERT INTO violations
SELECT 'ai', 'BD-37 삭제 Context의 키워드 잔존', 'ai.context_keyword', k.context_id,
       '삭제 시 함께 제거', 'context는 삭제됐는데 키워드가 남아 있다'
FROM ai.context_keyword k
JOIN core.context c ON c.id = k.context_id
WHERE c.deleted_at IS NOT NULL;

INSERT INTO violations
SELECT 'ai', 'BD-37 삭제 Context 상태가 CANCELLED 아님', 'ai.context_ai_state', s.context_id,
       'CANCELLED', format('embedding=%s keyword=%s', s.embedding_status, s.keyword_status)
FROM ai.context_ai_state s
JOIN core.context c ON c.id = s.context_id
WHERE c.deleted_at IS NOT NULL
  AND (s.embedding_status <> 'CANCELLED' OR s.keyword_status <> 'CANCELLED');

-- 하드 삭제로 생긴 고아 AI 행. teardown이 core만 지우므로 정상적으로 생길 수 있다.
INSERT INTO violations
SELECT 'ai', 'BD-37 고아 AI 상태 행(teardown 잔여)', 'ai.context_ai_state', s.context_id,
       '대응 core.context 있음', 'core.context에 행이 없다'
FROM ai.context_ai_state s
WHERE NOT EXISTS (SELECT 1 FROM core.context c WHERE c.id = s.context_id);

\echo '=== 전역 불변식 위반 상세 (앞 200건) ==='
SELECT owner, check_name, table_name, row_id, expected, actual
FROM violations
ORDER BY owner, check_name, row_id
LIMIT 200;

\echo '=== 검사별 집계 ==='
SELECT owner, check_name, count(*) AS violations
FROM violations
GROUP BY owner, check_name
ORDER BY owner, check_name;

\echo '=== 소유별 합계 (back 기준선은 BI-33의 시드 원인 삼분류 참고) ==='
SELECT
  count(*) FILTER (WHERE owner = 'back') AS back_violations,
  count(*) FILTER (WHERE owner = 'ai')   AS ai_violations
FROM violations;
