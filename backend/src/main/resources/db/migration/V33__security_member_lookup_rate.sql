-- ============================================================================
-- V33: Thêm cấu hình giới hạn tốc độ tra cứu thành viên (Rate Limit)
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
    editable
) VALUES (
    'SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE',
    '20',
    'INTEGER',
    1,
    300,
    'lần/phút',
    'SECURITY',
    1,
    'Số lần tối đa một tài khoản được tra cứu thông tin thành viên trong một phút. Vượt ngưỡng sẽ bị từ chối tạm thời.',
    true
) ON CONFLICT (config_key) DO NOTHING;
