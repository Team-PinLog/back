-- core 도메인 6개 테이블 (백엔드 소유 V2~V99 구간).
-- 근거: docs/static/06_데이터모델_및_무결성.md §2.3~2.8(컬럼), §3.1(부분 유니크), §3.2(CHECK), §3.3(조회 인덱스).
-- 6개 테이블은 FK와 부분 유니크로 서로 물려 있어 한 마이그레이션에서 함께 검증한다(S15P11A705-66).
-- social_account 관련 제약·인덱스(uq_social_active, member_id 인덱스)는 인증 PR 몫이라 여기 없다.
-- 유니크는 모두 활성행(deleted_at IS NULL) 부분 유니크다 — 전체 유니크로 걸면 삭제 후 재저장이 실패한다(BD-08).

-- place — 공용 장소 스냅샷(§2.3). 삭제하지 않으므로 deleted_at이 없고,
-- 저장 후 갱신하지 않으므로(스냅샷 원칙) updated_at은 감사용으로만 둔다.
CREATE TABLE core.place (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kakao_place_id VARCHAR(50)   NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    address        VARCHAR(200)  NOT NULL,
    road_address   VARCHAR(200),
    phone          VARCHAR(30),
    place_url      VARCHAR(300),
    lat            DECIMAL(10,7) NOT NULL,
    lng            DECIMAL(10,7) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- 카카오 좌표는 문자열 응답이라 파싱 오류를 DB가 잡아준다(§3.2)
    CONSTRAINT ck_place_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_place_lng CHECK (lng BETWEEN -180 AND 180)
);

-- 식별 기준은 kakao_place_id 하나. place만 전체 유니크다(§3.1).
CREATE UNIQUE INDEX uq_place_kakao ON core.place (kakao_place_id);

-- 지도 bbox 조회(§3.3)
CREATE INDEX ix_place_lat_lng ON core.place (lat, lng);

-- record — 한 User와 한 Place의 연결 단위(§2.4). 활성 Context를 최소 1개 가져야 한다(애플리케이션 보장).
CREATE TABLE core.record (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id  BIGINT      NOT NULL REFERENCES core.member (id),
    place_id   BIGINT      NOT NULL REFERENCES core.place (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

-- User당 활성 Record는 Place별 최대 1개(§4.1)
CREATE UNIQUE INDEX uq_record_active ON core.record (member_id, place_id) WHERE deleted_at IS NULL;

-- 지도·전체 Record 조회(§3.3)
CREATE INDEX ix_record_member ON core.record (member_id, deleted_at);

-- Place 기준 조회(§3.3)
CREATE INDEX ix_record_place ON core.record (place_id);

-- context — 불변 저장 이유(§2.5). 수정은 UPDATE가 아니라 교체 생성이므로 updated_at이 없다(BD-07).
-- member_id는 비정규화: 자연어 검색이 항상 본인 맥락으로 한정되므로 Record 조인 없이 후보를 좁힌다.
CREATE TABLE core.context (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    record_id         BIGINT      NOT NULL REFERENCES core.record (id),
    member_id         BIGINT      NOT NULL REFERENCES core.member (id),
    body              TEXT        NOT NULL,
    -- 최초 작성 시각(BD-25). 첫 생성은 자신의 created_at과 같고, 교체 생성 시 구 Context 값을 승계한다.
    -- 목록 정렬·날짜 표시의 기준 컬럼이다.
    origin_created_at TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT ck_context_body CHECK (length(btrim(body)) > 0)
);

-- Record 상세의 Context 조회(§3.3)
CREATE INDEX ix_context_record ON core.context (record_id, deleted_at);

-- 자연어 검색 범위 필터(§3.3)
CREATE INDEX ix_context_member ON core.context (member_id, deleted_at);

-- collection — Record를 묶어 공개하는 단위(§2.6). 생성 즉시 자동 발행(BD-23).
-- record_count는 활성 연결 수의 비정규화 값. 연결 추가·제거와 동일 트랜잭션에서 갱신한다.
CREATE TABLE core.collection (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id    BIGINT      NOT NULL REFERENCES core.member (id),
    title        VARCHAR(20) NOT NULL,
    is_published BOOLEAN     NOT NULL DEFAULT TRUE,
    published_at TIMESTAMPTZ,
    record_count INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT ck_collection_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_collection_count CHECK (record_count >= 0)
);

-- Shelf 조회(§3.3) — Shelf는 물리 테이블 없이 collection.member_id로 그룹핑한다(BD-15)
CREATE INDEX ix_collection_member ON core.collection (member_id, created_at DESC);

-- Feed 후보 스캔(§3.3)
CREATE INDEX ix_collection_feed ON core.collection (is_published, published_at DESC) WHERE deleted_at IS NULL;

-- collection_record — Collection과 Record의 연결(§2.7). Record는 Collection의 하위 데이터가 아니다.
CREATE TABLE core.collection_record (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collection_id BIGINT      NOT NULL REFERENCES core.collection (id),
    record_id     BIGINT      NOT NULL REFERENCES core.record (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

-- Collection 내 동일 Record 중복 금지(§4.1)
CREATE UNIQUE INDEX uq_colrec_active ON core.collection_record (collection_id, record_id) WHERE deleted_at IS NULL;

-- 컬렉션 내부 정렬: 담은 순서 최신순(§2.7, §3.3)
CREATE INDEX ix_colrec_collection ON core.collection_record (collection_id, created_at DESC);

-- Record 삭제 시 "해당 Record가 마지막인 Collection" 역조회(§2.7, 필수)
CREATE INDEX ix_colrec_record ON core.collection_record (record_id);

-- follow — Shelf 팔로우 관계(§2.8). Shelf 테이블이 없으므로 member를 직접 참조하고,
-- 그 덕에 자기 팔로우 금지를 DB CHECK로 보장한다. display_name은 팔로우 관계에 속한다.
CREATE TABLE core.follow (
    id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    followee_member_id BIGINT      NOT NULL REFERENCES core.member (id),
    follower_member_id BIGINT      NOT NULL REFERENCES core.member (id),
    display_name       VARCHAR(20),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT ck_follow_self CHECK (followee_member_id <> follower_member_id)
);

-- 동일 Shelf 중복 Follow 금지(§4.1)
CREATE UNIQUE INDEX uq_follow_active ON core.follow (followee_member_id, follower_member_id) WHERE deleted_at IS NULL;

-- Library 조회(§3.3)
CREATE INDEX ix_follow_follower ON core.follow (follower_member_id, created_at DESC);

-- 팔로워 수 집계(§3.3)
CREATE INDEX ix_follow_followee ON core.follow (followee_member_id);
