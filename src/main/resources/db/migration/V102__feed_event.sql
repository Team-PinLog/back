-- core.feed_event는 Feed 설계(AI 파트 소유)에서 나온 테이블이므로 V102(AI 구간)에 있습니다.
-- 백엔드 V2~ 작성 시 중복 정의하지 마세요.
--
-- 근거: Team-PinLog/back docs/feed/feed-event.md §2.
-- append-only 관측 로그. FK 없음(Collection 삭제 후에도 집계용으로 남아야 함),
-- deleted_at 없음. 보존 기간은 유한하게 두고 created_at 기준으로 주기 삭제한다.
CREATE TABLE core.feed_event (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id     BIGINT      NOT NULL,   -- 이벤트를 발생시킨 User(요청 본인)
    collection_id BIGINT      NOT NULL,   -- 대상 Collection
    place_id      BIGINT,                 -- CLICK/SAVE가 특정 Place를 향한 경우
    event         VARCHAR(20) NOT NULL,   -- IMPRESSION / CLICK / SAVE
    request_id    UUID        NOT NULL,   -- Feed Session 식별자
    position      INT,                    -- 응답 목록에서의 0-based 순서
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_feed_event_type
        CHECK (event IN ('IMPRESSION', 'CLICK', 'SAVE')),
    CONSTRAINT ck_feed_event_position
        CHECK (position IS NULL OR position >= 0)
);

-- 노출 패널티 조회의 핵심 인덱스: "이 User가 최근 이 Collection들을 몇 번 봤는가".
CREATE INDEX ix_feed_event_penalty
    ON core.feed_event (member_id, collection_id, created_at DESC);

CREATE INDEX ix_feed_event_request
    ON core.feed_event (request_id);

-- 보존 기간 경과분 정리 배치용.
CREATE INDEX ix_feed_event_created
    ON core.feed_event (created_at);
