-- ============================================================================
-- V143_1 (Checklist 6.1): Khung thời gian nhận ca và kết thúc ca trực bảo vệ vào cấu hình hệ thống
-- ============================================================================
-- Trước đây GuardScheduleService viết cứng ±5 phút trong checkIn, checkOut và autoMarkAbsentIfOverdue.
-- Nay đọc GUARD_SHIFT_CHECK_IN_WINDOW_MINUTES và GUARD_SHIFT_CHECK_OUT_WINDOW_MINUTES mỗi lần kiểm,
-- mặc định 5 phút để giữ nguyên hành vi cũ; sửa trên màn Cấu hình có hiệu lực ngay.
-- Cùng nhóm SECURITY (display_order 70–71 đã dùng ở V123).

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
) VALUES
    ('GUARD_SHIFT_CHECK_IN_WINDOW_MINUTES', '5', 'INTEGER', 1, 60, 'phút', 'SECURITY', 72,
     '[Ca trực bảo vệ] Khung thời gian cho phép nhận ca trước và sau giờ bắt đầu ca (phút)', true,
     'Bảo vệ được điểm danh nhận ca trong khoảng ±N phút quanh giờ bắt đầu ca. Quá mốc +N phút ca chưa nhận sẽ tự động chuyển VẮNG MẶT.'),
    ('GUARD_SHIFT_CHECK_OUT_WINDOW_MINUTES', '5', 'INTEGER', 1, 60, 'phút', 'SECURITY', 73,
     '[Ca trực bảo vệ] Thời gian cho phép kết thúc ca sau giờ tan ca (phút)', true,
     'Bảo vệ được điểm danh hoàn thành ca từ đúng giờ kết thúc đến sau N phút.')
ON CONFLICT (config_key) DO NOTHING;
