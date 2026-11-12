-- V116: Thêm 3 tham số cấu hình AI cho timeout check-entry và TTL cache quyền ra vào tại Edge
--   AI_ACCESS_CHECK_TIMEOUT_SECONDS : Thời gian timeout khi Edge gọi /api/access-control/check-entry (0.5 - 10s)
--   AI_ACCESS_DECISION_CACHE_TTL    : TTL cache quyết định cho phép ra vào tại Edge (1 - 600s)
--   AI_ACCESS_DENY_CACHE_TTL        : TTL cache quyết định từ chối ra vào tại Edge (1 - 600s)
-- Cùng nhóm AI_CONFIG (display_order 6–8 tiếp nối V75).
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
    ('AI_ACCESS_CHECK_TIMEOUT_SECONDS', '1.5', 'DECIMAL', 0.5, 10.0, 'giây', 'AI_CONFIG', 6,
     '[AI] Thời gian chờ tối đa khi thiết bị biên kiểm tra quyền truy cập (check-entry) qua API Backend (giây)', true),
    ('AI_ACCESS_DECISION_CACHE_TTL', '30.0', 'DECIMAL', 1.0, 600.0, 'giây', 'AI_CONFIG', 7,
     '[AI] Thời gian lưu bộ nhớ đệm kết quả xét duyệt quyền ra vào hợp lệ tại thiết bị biên (giây)', true),
    ('AI_ACCESS_DENY_CACHE_TTL', '5.0', 'DECIMAL', 1.0, 600.0, 'giây', 'AI_CONFIG', 8,
     '[AI] Thời gian lưu bộ nhớ đệm kết quả từ chối ra vào tại thiết bị biên (giây)', true)
ON CONFLICT (config_key) DO NOTHING;
