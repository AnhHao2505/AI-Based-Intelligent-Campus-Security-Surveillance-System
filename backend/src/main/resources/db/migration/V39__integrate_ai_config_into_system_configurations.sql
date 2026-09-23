-- ============================================================================
-- FLYWAY MIGRATION V39: INTEGRATE AI CONFIGURATION INTO SYSTEM_CONFIGURATIONS
-- ============================================================================

-- 1. Drop obsolete ai_configurations table
DROP TABLE IF EXISTS ai_configurations CASCADE;

-- 2. Seed AI_FACE_MATCH_THRESHOLD into system_configurations
INSERT INTO system_configurations (
    config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable, updated_at
) VALUES (
    'AI_FACE_MATCH_THRESHOLD', '0.75', 'DECIMAL', 0.50, 0.95, '(0.50 - 0.95)', 'AI_CONFIG', 1, 'Ngưỡng tin cậy nhận diện khuôn mặt (Face Match Threshold)', true, CURRENT_TIMESTAMP
) ON CONFLICT (config_key) DO NOTHING;
