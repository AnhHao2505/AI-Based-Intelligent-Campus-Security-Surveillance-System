INSERT INTO system_configurations (
    config_key, config_value, data_type, min_value, max_value, unit,
    config_group, display_order, description, editable, updated_at
) VALUES
    ('AI_AFTER_HOUR_START', '22:00', 'STRING', NULL, NULL, 'HH:mm', 'AI_CONFIG', 2,
     'Giờ bắt đầu khung giờ ngoài giờ hoạt động (định dạng HH:mm)', true, CURRENT_TIMESTAMP),
    ('AI_AFTER_HOUR_END', '06:00', 'STRING', NULL, NULL, 'HH:mm', 'AI_CONFIG', 3,
     'Giờ kết thúc khung giờ ngoài giờ hoạt động (định dạng HH:mm)', true, CURRENT_TIMESTAMP)
ON CONFLICT (config_key) DO NOTHING;
