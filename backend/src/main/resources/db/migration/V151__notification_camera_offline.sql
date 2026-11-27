-- ============================================================================
-- V151: Cảnh báo camera livestream mất kết nối đột ngột (Video Loss Alert)
-- 1. Bổ sung loại thông báo CAMERA_OFFLINE vào chk_notifications_type (giữ đủ 28 loại của V111)
-- 2. Bổ sung cấu hình động NOTIFICATION_CAMERA_OFFLINE_SECONDS (mặc định 45 giây)
-- ============================================================================

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type,
    ADD CONSTRAINT chk_notifications_type CHECK (type IN (
        'REQUEST_APPROVED',
        'REQUEST_REJECTED',
        'EXPIRING_SOON',
        'ACCESS_DENIED',
        'ADDED_TO_GROUP',
        'NEW_REQUEST_PENDING',
        'REQUEST_CANCELLED',
        'PENDING_OVERDUE',
        'EVENT_MODE_LIMIT_CHANGED',
        'EVENT_MODE_CHANGED',
        'EVENT_MODE_EXPIRING',
        'EVENT_MODE_SCHEDULED',
        'EVENT_MODE_SCHEDULE_STARTING',
        'EVENT_MODE_SCHEDULE_FAILED',
        'AREA_TYPE_CHANGED',
        'REQUEST_SYSTEM_CANCELLED',
        'GUEST_VISIT_PENDING',
        'GUEST_VISIT_APPROVED',
        'GUEST_VISIT_REJECTED',
        'GUEST_VISIT_REVOKED',
        'GUEST_VISIT_EXPIRED',
        'GUEST_PHOTO_REQUIRED',
        'ACCESS_PERMISSION_REVOKED',
        'GUEST_VISIT_CANCELLED_BY_SYSTEM',
        'REQUEST_FINISHED',
        'INCIDENT_ESCALATED',
        'INCIDENT_ESCALATION_NO_FM',
        'ACCOUNT_LOCKED',
        'CAMERA_OFFLINE'
    ));

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
    'NOTIFICATION_CAMERA_OFFLINE_SECONDS',
    '45',
    'INTEGER',
    15,
    600,
    'giây',
    'NOTIFICATION',
    5,
    'Thời gian xác nhận camera mất kết nối trước khi báo động',
    true,
    'Camera đang hoạt động bị mất luồng liên tục đạt ngưỡng giây này sẽ gửi cảnh báo tới Quản lý cơ sở vật chất, Quản trị viên và Bảo vệ.'
)
ON CONFLICT (config_key) DO NOTHING;
