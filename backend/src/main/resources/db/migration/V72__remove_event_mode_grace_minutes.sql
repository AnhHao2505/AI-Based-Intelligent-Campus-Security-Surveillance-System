-- ============================================================================
-- V72: bỏ cấu hình chết EVENT_MODE_GRACE_MINUTES (seed ở V54_2)
-- ============================================================================
-- Không có code nào đọc giá trị để dùng cho logic (chỉ có getter AreaService#getEventModeGraceMinutes không ai gọi),
-- nên khoá này mô tả một chức năng không tồn tại trên màn Cấu hình hệ thống -> ẩn khỏi hệ thống (Lucas, 09/10).
-- Chỉ xoá đúng 1 dòng system_configurations. KHÔNG đụng system_configuration_change_logs / audit cũ
-- (bảng lịch sử lưu config_key dạng chuỗi, không có khoá ngoại tới system_configurations).

DELETE FROM system_configurations WHERE config_key = 'EVENT_MODE_GRACE_MINUTES';
