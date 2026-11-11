-- V113 (PR-1 T1): chu kỳ quét của 2 job thông báo đơn truy cập vào cấu hình hệ thống (trước đây là cron trong application.yml,
-- đổi phải restart). Nhịp kỹ thuật NotificationScanScheduler chạy mỗi phút, đọc 2 giá trị này qua SystemConfigService
-- mỗi nhịp: sửa trên màn Cấu hình hệ thống có hiệu lực từ nhịp kế (≤ 1 phút), không restart.
--   NOTIFICATION_STARTING_SOON_SCAN_MINUTES   : chu kỳ quét nhắc "sắp đến giờ" (EXPIRING_SOON). Phải < NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES
--   NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES : chu kỳ quét báo FM đơn chờ duyệt tồn đọng (PENDING_OVERDUE). Phải ≤ NOTIFICATION_PENDING_OVERDUE_HOURS × 60
-- Ràng buộc chéo kiểm ở SystemConfigService khi lưu. Cùng nhóm NOTIFICATION (display_order 1–2 đã dùng ở V32).
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
    ('NOTIFICATION_STARTING_SOON_SCAN_MINUTES', '5', 'INTEGER', 1, 30, 'phút', 'NOTIFICATION', 3,
     'Chu kỳ quét để nhắc người có đơn đã duyệt sắp đến giờ vào khu vực (phút)', true),
    ('NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES', '60', 'INTEGER', 5, 1440, 'phút', 'NOTIFICATION', 4,
     'Chu kỳ quét để báo Facility Manager đơn chờ duyệt tồn đọng (phút)', true)
ON CONFLICT (config_key) DO NOTHING;
