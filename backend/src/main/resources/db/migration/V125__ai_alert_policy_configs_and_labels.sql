-- V125: cấu hình AI trên màn Cấu hình hệ thống.
-- 1) Sửa mô tả 8 khoá AI_CONFIG cho đúng nghĩa và dễ hiểu (giá trị đang dùng giữ nguyên).
--    AI_UNKNOWN_ALERT_COOLDOWN_SECONDS: mô tả cũ ghi "trên cùng một camera" nhưng AI tính theo từng người (track),
--    xem ai-service/src/core/frame_analysis_engine.py (track_last_unknown_alert_time).
-- 2) Thêm 3 khoá chính sách cảnh báo trước đây chỉ nằm trong .env / code của AI (display_order 9–11 tiếp nối V116).
-- 3) Ngưỡng khớp khuôn mặt: chỉ đổi 0.75 -> 0.50 khi giá trị vẫn là mặc định ban đầu của V39 (chưa ai chỉnh).
--    Môi trường đã được chỉnh trên màn Cấu hình giữ nguyên giá trị của mình.
-- 4) Khoảng cho phép của ngưỡng khớp khuôn mặt 0.50–0.95 -> 0.45–0.80, theo đánh giá trên camera CAM-002 (14/11/2026):
--    - 0.45: ngay trên điểm giống nhau cao nhất giữa 2 người khác nhau trong ảnh đăng ký (0.441);
--    - 0.80: trên điểm khớp đúng người cao nhất đo được qua camera (0.752); cao hơn thì gần như không nhận ra ai.
--    Bỏ đơn vị "(0.50 - 0.95)" vì màn hình đã hiện dòng giới hạn từ min/max. Giá trị ngoài khoảng mới được kéo về biên.

UPDATE system_configurations SET description = 'Điểm khớp khuôn mặt tối thiểu để xác nhận đúng người (cao hơn thì khắt khe hơn, dễ báo nhầm người lạ)'
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD';
UPDATE system_configurations SET description = 'Giờ bắt đầu ngoài giờ hoạt động, áp dụng cho mọi camera (HH:mm)'
WHERE config_key = 'AI_AFTER_HOUR_START';
UPDATE system_configurations SET description = 'Giờ kết thúc ngoài giờ hoạt động, áp dụng cho mọi camera (HH:mm)'
WHERE config_key = 'AI_AFTER_HOUR_END';
UPDATE system_configurations SET description = 'Thời gian giữ danh tính khi người tạm khuất khỏi khung hình hoặc quay lưng (giây)'
WHERE config_key = 'AI_TRACK_MAX_INACTIVE_SECONDS';
UPDATE system_configurations SET description = 'Khoảng cách tối thiểu giữa 2 cảnh báo người lạ của cùng một người (giây)'
WHERE config_key = 'AI_UNKNOWN_ALERT_COOLDOWN_SECONDS';
UPDATE system_configurations SET description = 'Thời gian chờ tối đa khi AI hỏi Backend quyền ra vào của một người (giây)'
WHERE config_key = 'AI_ACCESS_CHECK_TIMEOUT_SECONDS';
UPDATE system_configurations SET description = 'AI nhớ kết quả CHO PHÉP ra vào trong bao lâu trước khi hỏi lại Backend (giây)'
WHERE config_key = 'AI_ACCESS_DECISION_CACHE_TTL';
UPDATE system_configurations SET description = 'AI nhớ kết quả TỪ CHỐI ra vào trong bao lâu trước khi hỏi lại Backend (giây)'
WHERE config_key = 'AI_ACCESS_DENY_CACHE_TTL';

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
    ('AI_IDENTITY_GRACE_SECONDS', '1.5', 'DECIMAL', 0.5, 5.0, 'giây', 'AI_CONFIG', 9,
     'Thời gian chờ nhận diện khuôn mặt sau khi người bước vào vùng giám sát, trước khi phát cảnh báo (giây)', true),
    ('AI_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS', '30', 'INTEGER', 10, 300, 'giây', 'AI_CONFIG', 10,
     'Khoảng cách tối thiểu giữa 2 cảnh báo không có quyền ra vào của cùng một người (giây)', true),
    ('AI_SUSPICIOUS_MOTION_COOLDOWN_SECONDS', '5', 'INTEGER', 5, 300, 'giây', 'AI_CONFIG', 11,
     'Khoảng cách tối thiểu giữa 2 cảnh báo vật chuyển động lạ trên cùng một camera (giây)', true)
ON CONFLICT (config_key) DO NOTHING;

UPDATE system_configurations SET config_value = '0.50'
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD' AND config_value = '0.75';

UPDATE system_configurations SET min_value = 0.45, max_value = 0.80, unit = NULL
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD';
UPDATE system_configurations SET config_value = '0.80'
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD' AND CAST(config_value AS NUMERIC) > 0.80;
UPDATE system_configurations SET config_value = '0.45'
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD' AND CAST(config_value AS NUMERIC) < 0.45;
