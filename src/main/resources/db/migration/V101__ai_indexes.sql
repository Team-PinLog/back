-- ai 스키마 조회 인덱스 (AI 파트 소유, V100~V199 구간).
-- 벡터 검색은 정확 cosine이며 MVP에서 ANN 인덱스(HNSW/IVFFlat)는 두지 않는다.

-- 개인 검색: user_id로 후보를 좁히고 is_deleted로 삭제분을 제외한다.
CREATE INDEX idx_context_embedding_user_active
    ON ai.context_embedding (user_id, is_deleted);

-- Record 단위 결과 집계·역조회.
CREATE INDEX idx_context_embedding_record
    ON ai.context_embedding (record_id);

-- 재스캔: 만료된 stale 상태를 status + updated_at으로 스캔한다.
CREATE INDEX idx_context_ai_state_embedding
    ON ai.context_ai_state (embedding_status, updated_at);
CREATE INDEX idx_context_ai_state_keyword
    ON ai.context_ai_state (keyword_status, updated_at);

-- 프리셋 사용 빈도 분석·역조회.
CREATE INDEX idx_context_keyword_keyword
    ON ai.context_keyword (keyword_id);
