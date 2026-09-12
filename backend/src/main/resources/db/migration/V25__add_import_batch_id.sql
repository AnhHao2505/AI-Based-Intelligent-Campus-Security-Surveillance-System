-- ============================================================================
-- FLYWAY MIGRATION V25: ADD IMPORT_BATCH_ID TO USERS TABLE (FA26SE040)
-- Gắn nhãn truy vết tài khoản theo từng lần import hàng loạt
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS import_batch_id UUID;

CREATE INDEX IF NOT EXISTS idx_users_import_batch_id
    ON users (import_batch_id) WHERE import_batch_id IS NOT NULL;
