-- V115 (PR-5, Lucas chốt 11/11/2026): đơn giản hoá giới hạn chế độ sự kiện.
--   EV-N1: bỏ trần thời lượng mỗi phiên và ngân sách giờ theo cửa sổ -> bỏ EVENT_MODE_MAX_HOURS, EVENT_MODE_WINDOW_DAYS,
--          EVENT_MODE_BUDGET_HOURS
--   EV-N2: bỏ giới hạn số lịch chờ mỗi khu -> bỏ EVENT_MODE_MAX_SCHEDULES_PER_AREA
-- Xoá hẳn 4 dòng: không bảng nào có FK tới system_configurations. system_configuration_change_logs.config_key chỉ là
-- VARCHAR (V32) nên lịch sử thay đổi của 4 khoá vẫn giữ nguyên; audit_logs (SYSTEM_CONFIG) cũng giữ nguyên.
-- Màn Cấu hình hệ thống đọc danh sách từ bảng nên 4 khoá tự biến mất, không cần sửa frontend.
-- EV-N7 / EV-N8 (khung ban đêm cho lịch sự kiện) không cần bảng / cột mới.
DELETE FROM system_configurations
WHERE config_key IN (
    'EVENT_MODE_MAX_HOURS',
    'EVENT_MODE_WINDOW_DAYS',
    'EVENT_MODE_BUDGET_HOURS',
    'EVENT_MODE_MAX_SCHEDULES_PER_AREA'
);
