-- ============================================================================
-- V77: Tham số nhật ký ra vào tại camera (camera_access_logs, V74)
-- ============================================================================
-- ENTRY_LOG_DEBOUNCE_SECONDS: cùng người + cùng camera trong khoảng này chỉ ghi 1 dòng log.
--   Thay hằng DEBOUNCE_SECONDS = 30 viết cứng trong CameraAccessLogService; đọc mỗi lần ghi nên sửa có hiệu lực ngay.
-- ENTRY_LOG_RETENTION_DAYS: job dọn hằng ngày xoá log cũ hơn N ngày. Cron là tham số vận hành
--   (icss.scheduler.access-log-retention-cron), không nằm trong DB.
-- Chỉ thêm khoá cấu hình, không seed dữ liệu khác. Cùng nhóm SECURITY (display_order 40–48 đã dùng).

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
    ('ENTRY_LOG_DEBOUNCE_SECONDS', '30', 'INTEGER', 0, 600, 'giây', 'SECURITY', 50,
     '[Nhật ký ra vào] Cùng một người đi qua cùng một camera trong bao nhiêu giây thì chỉ ghi một lượt (0 = ghi mọi lượt)', true),
    ('ENTRY_LOG_RETENTION_DAYS', '90', 'INTEGER', 7, 3650, 'ngày', 'SECURITY', 51,
     '[Nhật ký ra vào] Giữ nhật ký ra vào tại camera bao nhiêu ngày; job dọn hằng ngày xoá bản ghi cũ hơn', true)
ON CONFLICT (config_key) DO NOTHING;
