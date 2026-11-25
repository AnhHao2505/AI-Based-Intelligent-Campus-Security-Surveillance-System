-- ============================================================================
-- V146 (Checklist 6.1): Đưa ngưỡng phát hiện khuôn mặt SCRFD (DEFAULT_FACE_CONFIDENCE)
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
    'AI_FACE_CONFIDENCE',
    '0.45',
    'DECIMAL',
    0.20,
    0.85,
    NULL,
    'AI_CONFIG',
    10,
    'Ngưỡng phát hiện khuôn mặt (SCRFD)',
    true,
    'Độ tin cậy tối thiểu để AI xác nhận vùng phát hiện là khuôn mặt trước khi so khớp danh tính. Tăng: giảm bắt nhầm cổ áo/tóc, nhưng người đứng xa hoặc nghiêng dễ bị báo người lạ. Giảm: dễ bắt mặt ở xa/góc nghiêng hơn, nhưng dễ bắt nhầm vùng nhiễu.'
)
ON CONFLICT (config_key) DO NOTHING;
