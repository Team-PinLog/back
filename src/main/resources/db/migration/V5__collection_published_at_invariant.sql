-- Collection은 MVP에서 생성 즉시 자동 발행된다(BD-23, BD-29).
-- 기존 NULL은 생성 시각으로 복구한다.
UPDATE core.collection
SET published_at = created_at
WHERE published_at IS NULL;

-- 발행된 행은 발행 시각을 반드시 갖는다. 컬럼 자체를 NOT NULL로 만들지 않는 이유는
-- published_at이 "is_published는 false일 수 있다"는 전제 위에 존재하기 때문이다(BD-23).
-- 쌍조건(is_published = (published_at IS NOT NULL))이 아니라 함의 한 방향만 거는 이유는
-- 발행 취소가 들어와도 과거 발행 시각을 남길 수 있어야 하기 때문이다.
ALTER TABLE core.collection
    ADD CONSTRAINT ck_collection_published_at
        CHECK (NOT is_published OR published_at IS NOT NULL);
