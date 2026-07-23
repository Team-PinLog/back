-- ai 스키마 파생 데이터 테이블 (AI 파트 소유, V100~V199 구간).
-- 공용 계약: Team-PinLog/docs static/05_AI_설계.md §12.
-- Context는 불변 엔티티이므로 본문 버전 컬럼(context_version)을 두지 않는다.
-- stale 결과 차단은 context_ai_state의 CANCELLED가 담당한다.

-- 프리셋 Keyword 목록과 임베딩. embedding은 부트스트랩 적재 시 채워진다.
CREATE TABLE ai.keyword_preset (
    id                INT          PRIMARY KEY,
    code              VARCHAR(50)  NOT NULL UNIQUE,
    display_name      VARCHAR(50)  NOT NULL,
    category          VARCHAR(30)  NOT NULL,
    description       TEXT         NOT NULL,
    examples          TEXT[]       NOT NULL,
    embedding         VECTOR(1536) NOT NULL,
    embedding_profile VARCHAR(100) NOT NULL,
    visibility        VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    is_active         BOOLEAN      NOT NULL DEFAULT true,
    version           INT          NOT NULL DEFAULT 1,
    CONSTRAINT ck_keyword_preset_visibility
        CHECK (visibility IN ('PUBLIC', 'PRIVATE_ONLY', 'BLOCKED'))
);

-- Context별 AI 처리 상태. embedding/keyword 두 단계가 독립 전이한다.
CREATE TABLE ai.context_ai_state (
    context_id       BIGINT      PRIMARY KEY,
    embedding_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    keyword_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count      INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_context_ai_state_embedding
        CHECK (embedding_status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    CONSTRAINT ck_context_ai_state_keyword
        CHECK (keyword_status   IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    CONSTRAINT ck_context_ai_state_retry
        CHECK (retry_count BETWEEN 0 AND 3)
);

-- Context 본문 벡터. user_id/record_id는 검색 성능용 비정규화 값(core FK 없음).
-- is_deleted는 백엔드만 변경하는 일반 컬럼이며 PK는 context_id 단독이다
-- (UPSERT ON CONFLICT (context_id)를 위해).
CREATE TABLE ai.context_embedding (
    context_id        BIGINT       PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    record_id         BIGINT       NOT NULL,
    embedding         VECTOR(1536) NOT NULL,
    embedding_profile VARCHAR(100) NOT NULL,
    is_deleted        BOOLEAN      NOT NULL DEFAULT false,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Context에 대한 최종 Keyword 판정. 원본 단위는 Context.
CREATE TABLE ai.context_keyword (
    context_id     BIGINT       NOT NULL,
    keyword_id     INT          NOT NULL REFERENCES ai.keyword_preset(id),
    confidence     NUMERIC(4,3),
    preset_version INT          NOT NULL,
    PRIMARY KEY (context_id, keyword_id),
    CONSTRAINT ck_context_keyword_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
);

-- 프리셋 보정용 분석 데이터. 사용자에게 공개하지 않는다.
CREATE TABLE ai.context_keyword_analysis (
    context_id         BIGINT       PRIMARY KEY,
    preset_version     INT          NOT NULL,
    unmatched_concepts JSONB        NOT NULL DEFAULT '[]'::jsonb,
    model_profile      VARCHAR(100) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
