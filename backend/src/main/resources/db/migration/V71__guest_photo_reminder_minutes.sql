-- ============================================================================
-- V71: BR-GV-39 — nhắc ADMIN gắn ảnh khách trước giờ bắt đầu lượt
-- ============================================================================
-- Khách chỉ được nhận diện / cho vào khi đã đăng ký khuôn mặt tại quầy (PHOTO_READY, BR-GV-38).
-- Job lượt khách (GuestVisitJobService) nhắc ADMIN 1 lần (thông báo GUEST_PHOTO_REQUIRED) khi lượt APPROVED
-- còn khách chưa PHOTO_READY và giờ hiện tại đã vào cửa sổ [start − GUEST_PHOTO_REMINDER_MINUTES_BEFORE, start).
-- Đọc lúc chạy qua SystemConfigService: sửa trên màn Cấu hình hệ thống có hiệu lực ngay.
-- Chỉ thêm khoá cấu hình, không seed dữ liệu khác. Cùng nhóm / định dạng với các khoá GUEST ở V60 (display_order 70–77).

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
    'GUEST_PHOTO_REMINDER_MINUTES_BEFORE',
    '60',
    'INTEGER',
    5,
    1440,
    'phút',
    'GUEST',
    78,
    '[Khách] Nhắc quản trị viên gắn ảnh khách trước giờ bắt đầu lượt bao nhiêu phút (lượt đã duyệt còn khách chưa có ảnh)',
    true
) ON CONFLICT (config_key) DO NOTHING;
