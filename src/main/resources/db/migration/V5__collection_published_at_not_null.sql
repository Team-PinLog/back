-- Collection은 MVP에서 생성 즉시 자동 발행된다(BD-23, BD-29).
-- 기존 NULL은 생성 시각으로 복구하고 DB 직접 INSERT도 발행 시각을 갖도록 기본값을 둔다.
UPDATE core.collection
SET published_at = created_at
WHERE published_at IS NULL;

ALTER TABLE core.collection
    ALTER COLUMN published_at SET DEFAULT now(),
    ALTER COLUMN published_at SET NOT NULL;
