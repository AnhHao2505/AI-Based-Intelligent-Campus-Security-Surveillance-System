-- ============================================================================
-- V145 (Checklist 6.1): Đưa ngưỡng phát hiện người YOLO (DEFAULT_DETECTION_CONFIDENCE)
-- vào bảng cấu hình hệ thống để Admin chỉnh động trên màn Cấu hình (nhóm AI_CONFIG).
-- ============================================================================

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
    editable,
    tooltip
) VALUES (
    'AI_DETECTION_CONFIDENCE',
    '0.35',
    'DECIMAL',
    0.15,
    0.85,
    NULL,
    'AI_CONFIG',
    9,
    'Ngưỡng phát hiện người (YOLO)',
    true,
    'Độ tin cậy tối thiểu để AI xác định một đối tượng là người. Tăng: giảm báo nhầm đồ vật là người, nhưng người đứng xa hoặc bị khuất có thể bị bỏ sót. Giảm: phát hiện nhạy hơn ở khoảng cách xa, nhưng dễ nhận nhầm bóng/vật thể.'
)
ON CONFLICT (config_key) DO NOTHING;
