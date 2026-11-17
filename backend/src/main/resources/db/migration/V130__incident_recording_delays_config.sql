-- Migration V127: Thêm cấu hình độ trễ bắt đầu và kết thúc ghi hình sự cố an ninh
-- INCIDENT_RECORDING_START_DELAY_SECONDS: Bắt đầu ghi hình sau N giây kể từ phát hiện đầu tiên (chống ghi hình rác)
-- INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS: Kết thúc ghi hình sau N giây không còn phát hiện sự cố
-- Cả 2 tham số thuộc nhóm AI_CONFIG, cho phép Admin tinh chỉnh trên giao diện Quản lý cấu hình.

INSERT INTO system_configurations (
    config_key,
    config_value,
    data_type,
    min_value,
    max_value,
    unit,
    config_group,
    display_order,
    description,
    editable
) VALUES
    ('INCIDENT_RECORDING_START_DELAY_SECONDS', '0.0', 'DECIMAL', 0.0, 30.0, 'giây', 'AI_CONFIG', 13,
     'Thời gian trễ từ khi phát hiện sự cố đầu tiên đến khi bắt đầu ghi hình video sự cố (giây, 0 = ghi ngay lập tức)', true),
    ('INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS', '0.0', 'DECIMAL', 0.0, 60.0, 'giây', 'AI_CONFIG', 14,
     'Thời gian chờ sau khi hết phát hiện sự cố trước khi đóng và lưu video sự cố (giây, 0 = dừng ngay khi hết phát hiện)', true)
ON CONFLICT (config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    min_value = EXCLUDED.min_value,
    description = EXCLUDED.description;
