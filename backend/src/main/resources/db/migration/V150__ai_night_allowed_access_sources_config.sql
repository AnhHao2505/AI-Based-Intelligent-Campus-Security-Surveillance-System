-- ============================================================================
-- V150: Chuyển danh sách nguồn quyền được miễn giờ giới nghiêm
-- (NIGHT_ALLOWED_ACCESS_SOURCES) từ .env ai-service sang System Configuration
-- (nhóm AI_CONFIG) để Admin chỉnh động, ghi audit và đồng bộ tức thì xuống AI.
-- Mặc định giữ đúng 4 nguồn: ASSIGNED_PERSONNEL, ACCESS_REQUEST, GUEST_VISIT,
-- INCIDENT_RESPONSE (ACCESS_LEVEL và OPEN_EVENT không miễn mặc định).
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
    'AI_NIGHT_ALLOWED_ACCESS_SOURCES',
    'ASSIGNED_PERSONNEL,ACCESS_REQUEST,GUEST_VISIT,INCIDENT_RESPONSE',
    'STRING',
    NULL,
    NULL,
    NULL,
    'AI_CONFIG',
    12,
    'Nguồn quyền miễn giờ giới nghiêm',
    true,
    'Các nguồn cấp quyền được miễn cảnh báo đột nhập ngoài giờ khi Backend đã cho phép vào khu vực. Bảo vệ (GUARD) luôn được miễn khi được phép vào. Thay đổi có hiệu lực ngay với AI Edge.'
)
ON CONFLICT (config_key) DO NOTHING;
