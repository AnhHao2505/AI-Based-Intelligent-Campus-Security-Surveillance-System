-- ============================================================================
-- FLYWAY MIGRATION V75: ADD AI TRACKING & ALERT COOLDOWN SYSTEM CONFIGURATIONS
-- ============================================================================

INSERT INTO system_configurations (
    config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable, updated_at
) VALUES
    ('AI_TRACK_MAX_INACTIVE_SECONDS', '10.0', 'DECIMAL', 3.0, 30.0, 'giây', 'AI_CONFIG', 4,
     'Thời gian tối đa duy trì vết đối tượng khi quay lưng hoặc mất dấu tạm thời (giây)', true, CURRENT_TIMESTAMP),
    ('AI_UNKNOWN_ALERT_COOLDOWN_SECONDS', '30', 'INTEGER', 10, 120, 'giây', 'AI_CONFIG', 5,
     'Thời gian chờ tối thiểu giữa 2 cảnh báo đối tượng chưa rõ danh tính trên cùng một camera (giây)', true, CURRENT_TIMESTAMP)
ON CONFLICT (config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    min_value = EXCLUDED.min_value,
    max_value = EXCLUDED.max_value,
    unit = EXCLUDED.unit,
    description = EXCLUDED.description,
    display_order = EXCLUDED.display_order;
