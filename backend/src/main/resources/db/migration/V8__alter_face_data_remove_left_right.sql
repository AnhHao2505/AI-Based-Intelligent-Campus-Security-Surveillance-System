-- ============================================================================
-- FLYWAY MIGRATION V8: ALTER FACE_DATA REMOVE LEFT AND RIGHT ANGLES (FA26SE040)
-- PostgreSQL 16 + pgvector Extension
-- ============================================================================

ALTER TABLE face_data
    DROP COLUMN IF EXISTS image_left_url,
    DROP COLUMN IF EXISTS image_right_url,
    DROP COLUMN IF EXISTS embedding_left,
    DROP COLUMN IF EXISTS embedding_right;
