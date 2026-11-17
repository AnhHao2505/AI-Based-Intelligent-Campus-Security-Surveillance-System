-- Migration V128: Thêm cấu hình thời lượng mỗi đoạn video ghi hình camera định kỳ (RECORDING_SEGMENT_SECONDS)
-- Cho phép Admin cấu hình độ dài mỗi file MP4 (giây), mặc định 60s (1 phút).
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
    ('RECORDING_SEGMENT_SECONDS', '60', 'INTEGER', 10, 3600, 'giây', 'AI_CONFIG', 15,
     'Thời lượng mỗi phân đoạn video ghi hình camera định kỳ (giây, mặc định 60 giây = 1 phút/file)', true)
ON CONFLICT (config_key) DO NOTHING;
