-- 공통 기반 (AI 파트 소유, 버전 구간 컨벤션의 V1).
-- core / ai 스키마를 단독 생성한다. IF NOT EXISTS를 쓰지 않아,
-- 다른 마이그레이션이 스키마를 재선언하면 명시적으로 실패하게 한다(소유권 경계 보호).
CREATE SCHEMA core;
CREATE SCHEMA ai;

-- pgvector. 이미지에 사전 설치되어 있을 수 있으므로 IF NOT EXISTS.
CREATE EXTENSION IF NOT EXISTS vector;
