-- V128: khoảng cách tối thiểu giữa 2 cảnh báo người lạ trên cùng một camera.
-- Trước đây ghi cứng 3.0 giây trong AI (CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS); nay sửa được trên màn Cấu hình hệ thống
-- và áp ngay xuống AI như các khoá AI_CONFIG khác. Chống dồn nhiều cảnh báo người lạ cùng lúc khi một nhóm người
-- cùng bước vào khung hình; mỗi người vẫn có cooldown riêng (AI_UNKNOWN_ALERT_COOLDOWN_SECONDS).
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
    ('AI_CAMERA_UNKNOWN_MIN_INTERVAL_SECONDS', '3', 'INTEGER', 1, 60, 'giây', 'AI_CONFIG', 14,
     'Khoảng cách tối thiểu giữa 2 cảnh báo người lạ trên cùng một camera (giây)', true)
ON CONFLICT (config_key) DO NOTHING;
