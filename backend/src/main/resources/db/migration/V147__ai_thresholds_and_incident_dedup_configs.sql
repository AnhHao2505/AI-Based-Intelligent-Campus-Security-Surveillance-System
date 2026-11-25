-- V147: Tổng hợp tinh chỉnh cấu hình AI & An ninh
-- 1. Thu hẹp khoảng cấu hình 3 ngưỡng AI về vùng hoạt động ổn định:
--    - AI_FACE_MATCH_THRESHOLD: [0.50, 0.65]
--    - AI_DETECTION_CONFIDENCE: [0.30, 0.65] (bỏ chữ YOLO)
--    - AI_FACE_CONFIDENCE: [0.30, 0.65] (bỏ chữ SCRFD)
-- 2. Xếp 3 ngưỡng AI liền kề nhau (display_order 1, 2, 3)
-- 3. Tăng cooldown mặc định báo lại người không có quyền từ 30s lên 60s
-- 4. Thêm cấu hình cửa sổ gộp sự cố trùng người (INCIDENT_DEDUP_WINDOW_MINUTES = 5 phút, tab SECURITY)

UPDATE system_configurations
SET description   = 'Ngưỡng trùng khớp danh tính',
    min_value     = 0.50,
    max_value     = 0.65,
    display_order = 1,
    config_value  = CASE
        WHEN CAST(config_value AS NUMERIC) > 0.65 THEN '0.65'
        WHEN CAST(config_value AS NUMERIC) < 0.50 THEN '0.50'
        ELSE config_value
    END
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD';

UPDATE system_configurations
SET description   = 'Ngưỡng phát hiện người',
    min_value     = 0.30,
    max_value     = 0.65,
    display_order = 2,
    config_value  = CASE
        WHEN CAST(config_value AS NUMERIC) > 0.65 THEN '0.65'
        WHEN CAST(config_value AS NUMERIC) < 0.30 THEN '0.30'
        ELSE config_value
    END
WHERE config_key = 'AI_DETECTION_CONFIDENCE';

UPDATE system_configurations
SET description   = 'Ngưỡng phát hiện khuôn mặt',
    min_value     = 0.30,
    max_value     = 0.65,
    display_order = 3,
    config_value  = CASE
        WHEN CAST(config_value AS NUMERIC) > 0.65 THEN '0.65'
        WHEN CAST(config_value AS NUMERIC) < 0.30 THEN '0.30'
        ELSE config_value
    END
WHERE config_key = 'AI_FACE_CONFIDENCE';

UPDATE system_configurations SET display_order = 4 WHERE config_key = 'AI_AFTER_HOUR_START';
UPDATE system_configurations SET display_order = 5 WHERE config_key = 'AI_AFTER_HOUR_END';
UPDATE system_configurations SET display_order = 6 WHERE config_key = 'AI_UNKNOWN_ALERT_COOLDOWN_SECONDS';

UPDATE system_configurations
SET display_order = 7,
    config_value  = CASE WHEN config_value = '30' THEN '60' ELSE config_value END
WHERE config_key = 'AI_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS';

UPDATE system_configurations SET display_order = 8  WHERE config_key = 'AI_SUSPICIOUS_MOTION_COOLDOWN_SECONDS';
UPDATE system_configurations SET display_order = 10 WHERE config_key = 'RECORDING_RETENTION_HOURS';
UPDATE system_configurations SET display_order = 11 WHERE config_key = 'INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS';

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
    'INCIDENT_DEDUP_WINDOW_MINUTES',
    '5',
    'INTEGER',
    1,
    60,
    'phút',
    'AI_CONFIG',
    9,
    'Cửa sổ gộp sự cố trùng người',
    true,
    'Trong khoảng thời gian này (phút), nếu cùng một người có danh tính tiếp tục vi phạm tại cùng camera khi sự cố trước đó chưa được xử lý xong, hệ thống sẽ gộp vào sự cố đang mở thay vì tạo thẻ sự cố mới.'
)
ON CONFLICT (config_key) DO UPDATE SET
    config_group  = 'AI_CONFIG',
    display_order = 9;
