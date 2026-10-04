-- ============================================================================
-- V66: Cấu hình bảo lãnh đơn nhóm (BR-RQ-03/04) và đánh dấu thành viên được bảo lãnh
-- ============================================================================

-- 1. Thêm cột sponsored vào bảng access_request_members
ALTER TABLE access_request_members
    ADD COLUMN sponsored BOOLEAN NOT NULL DEFAULT false;

-- 2. Thêm cấu hình ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES vào system_configurations
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
    'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES',
    'CONFIDENTIAL_CONTACT_REQUIRED',
    'STRING',
    NULL,
    NULL,
    NULL,
    'ACCESS_REQUEST',
    6,
    'Danh sách loại khu vực cho phép bảo lãnh thành viên trong đơn nhóm (phân tách bởi dấu phẩy, mặc định: CONFIDENTIAL_CONTACT_REQUIRED)',
    true
) ON CONFLICT (config_key) DO NOTHING;
