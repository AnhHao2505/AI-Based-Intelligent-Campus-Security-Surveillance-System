-- ============================================================================
-- V142: rút gọn nhóm AI_CONFIG trên màn Cấu hình hệ thống từ 17 xuống 8 khoá
-- ============================================================================
-- Chỉ giữ khoá admin có lý do nghiệp vụ để đổi và đoán được kết quả mà không cần hiểu thuật toán.
-- 9 khoá kỹ thuật bỏ khỏi bảng; AI đọc giá trị từ .env / ai-service/src/config.py (tên biến ghi bên phải).
-- Backend thôi gửi các khoá này sang AI (SystemConfigService.getAiRuntimeConfigMap), nên AI dùng giá trị .env
-- sau lần khởi động lại kế tiếp. Lịch sử thay đổi trong system_configuration_change_logs giữ nguyên (không có FK).
--   AI_TRACK_MAX_INACTIVE_SECONDS            -> DEFAULT_TRACK_MAX_INACTIVE_SECONDS
--   AI_IDENTITY_GRACE_SECONDS                -> ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS
--   AI_IDENTITY_HOLD_SECONDS                 -> ACCESS_RESOLUTION_HOLD_SECONDS
--   AI_CAMERA_UNKNOWN_MIN_INTERVAL_SECONDS   -> CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS
--   AI_ACCESS_CHECK_TIMEOUT_SECONDS          -> ACCESS_CHECK_TIMEOUT_SECONDS
--   AI_ACCESS_DECISION_CACHE_TTL             -> ACCESS_DECISION_CACHE_TTL
--   AI_ACCESS_DENY_CACHE_TTL                 -> ACCESS_DENY_CACHE_TTL
--   RECORDING_SEGMENT_SECONDS                -> RECORDING_SEGMENT_SECONDS
--   INCIDENT_RECORDING_START_DELAY_SECONDS   -> INCIDENT_RECORDING_START_DELAY_SECONDS
--
-- 8 khoá còn lại: tiêu đề ngắn (màn hình hiện đơn vị và dòng tối thiểu / tối đa ngay sau ô nhập), tooltip giải thích
-- tác dụng khi tăng / giảm; display_order 1–8 (trước đây trùng 13 và 14 giữa V127/V128/V130).
--
-- Sửa khoảng AI_FACE_MATCH_THRESHOLD: V125 đặt 0.45–0.80 theo đo trên CAM-002 (14/11/2026: điểm giống nhau cao nhất
-- giữa 2 người khác nhau 0.441, khớp đúng người cao nhất 0.752), nhưng V125_1 chạy sau ghi đè thành 0.30–0.70
-- và unit '(0.30 - 0.70)'. Dưới 0.45 thì AI nhận nhầm người. Đưa về 0.45–0.80, unit NULL, kéo giá trị ngoài khoảng về biên.

DELETE FROM system_configurations
WHERE config_key IN (
    'AI_TRACK_MAX_INACTIVE_SECONDS',
    'AI_IDENTITY_GRACE_SECONDS',
    'AI_IDENTITY_HOLD_SECONDS',
    'AI_CAMERA_UNKNOWN_MIN_INTERVAL_SECONDS',
    'AI_ACCESS_CHECK_TIMEOUT_SECONDS',
    'AI_ACCESS_DECISION_CACHE_TTL',
    'AI_ACCESS_DENY_CACHE_TTL',
    'RECORDING_SEGMENT_SECONDS',
    'INCIDENT_RECORDING_START_DELAY_SECONDS'
);

UPDATE system_configurations
SET description   = 'Ngưỡng khớp khuôn mặt',
    tooltip       = 'Điểm giống tối thiểu để AI xác nhận đúng người đã đăng ký. Tăng: khó nhận nhầm người khác, nhưng người đã đăng ký dễ bị báo là người lạ hơn. Giảm: dễ nhận ra hơn, nhưng dễ nhận nhầm người. Nên để 0.50, chỉ chỉnh khi camera báo nhầm thường xuyên. Cũng dùng để kiểm tra ảnh khuôn mặt bị trùng khi đăng ký.',
    min_value     = 0.45,
    max_value     = 0.80,
    unit          = NULL,
    display_order = 1,
    config_value  = CASE
        WHEN CAST(config_value AS NUMERIC) > 0.80 THEN '0.80'
        WHEN CAST(config_value AS NUMERIC) < 0.45 THEN '0.45'
        ELSE config_value
    END
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD';

UPDATE system_configurations
SET description   = 'Bắt đầu ngoài giờ',
    tooltip       = 'Từ giờ này tới giờ kết thúc, chỉ bảo vệ, người được phân công, người có đơn ra vào đã duyệt và khách có lịch hẹn mới được vào. Người chỉ có cấp truy cập thông thường cũng bị cảnh báo xâm nhập. Áp dụng cho toàn trường.',
    display_order = 2
WHERE config_key = 'AI_AFTER_HOUR_START';

UPDATE system_configurations
SET description   = 'Kết thúc ngoài giờ',
    tooltip       = 'Sau giờ này các khu vực trở lại hoạt động bình thường. Được phép qua ngày, ví dụ 22:00 tới 06:00. Không được trùng giờ bắt đầu.',
    display_order = 3
WHERE config_key = 'AI_AFTER_HOUR_END';

UPDATE system_configurations
SET description   = 'Báo lại người lạ sau',
    tooltip       = 'Nếu cùng một người lạ vẫn ở trong khu vực, sau khoảng này mới gửi thêm cảnh báo. Tăng thì bớt cảnh báo lặp, giảm thì bảo vệ được nhắc dày hơn. Người lạ khác vẫn được báo riêng.',
    display_order = 4
WHERE config_key = 'AI_UNKNOWN_ALERT_COOLDOWN_SECONDS';

UPDATE system_configurations
SET description   = 'Báo lại người không có quyền sau',
    tooltip       = 'Người đã được nhận ra nhưng không có quyền vào khu vực: sau khoảng này mới gửi thêm cảnh báo về chính người đó trên cùng camera. Tăng thì bớt cảnh báo lặp.',
    display_order = 5
WHERE config_key = 'AI_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS';

UPDATE system_configurations
SET description   = 'Báo lại chuyển động lạ sau',
    tooltip       = 'Camera thấy vật chuyển động trong vùng giám sát nhưng không nhận ra là người (ví dụ người bò, người che kín). Sau khoảng này mới báo tiếp trên cùng camera. Tăng nếu cây, rèm hoặc ánh đèn gây báo lặp liên tục.',
    display_order = 6
WHERE config_key = 'AI_SUSPICIOUS_MOTION_COOLDOWN_SECONDS';

UPDATE system_configurations
SET description   = 'Giữ video định kỳ',
    tooltip       = 'Video camera ghi liên tục được giữ trong khoảng này rồi tự xoá để giải phóng dung lượng. Video sự cố nằm ở kho riêng nên không bị xoá theo. Tăng thì tốn thêm dung lượng lưu trữ.',
    display_order = 7
WHERE config_key = 'RECORDING_RETENTION_HOURS';

UPDATE system_configurations
SET description   = 'Ghi thêm sau sự cố',
    tooltip       = 'Khi không còn thấy đối tượng vi phạm, video sự cố ghi thêm khoảng này rồi mới dừng, để thấy rõ đối tượng đã rời đi đâu. 0 = dừng ngay.',
    display_order = 8
WHERE config_key = 'INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS';
