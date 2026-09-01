-- ============================================================================
-- FLYWAY MIGRATION V5: CREATE FACE_DATA & CAMERA MANAGEMENT TABLES (FA26SE040)
-- PostgreSQL 16 + pgvector Extension
-- ============================================================================

-- 1. Enable Required Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- 2. Table: face_data (Dataset khuôn mặt phục vụ AI nhận diện & đối soát)
CREATE TABLE IF NOT EXISTS face_data (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL, -- MSSV hoặc MSNV
    full_name VARCHAR(100) NOT NULL,
    image_front_url VARCHAR(512) NOT NULL,
    image_left_url VARCHAR(512) NOT NULL,
    image_right_url VARCHAR(512) NOT NULL,
    embedding_front vector(512) NOT NULL,
    embedding_left vector(512) NOT NULL,
    embedding_right vector(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_face_data_code ON face_data(code);
CREATE INDEX idx_face_data_full_name ON face_data(full_name);
