-- ============================================================================
-- V84: Leo thang sự cố khi không có Quản lý CSVC nào đang hoạt động (S5, Lucas chốt 20/10)
-- ============================================================================
-- Trước đây: không có FM active -> bản ghi bị đóng ESCALATED với 0 người nhận, không bao giờ thử lại.
-- Từ V84: bản ghi giữ mở, job lần sau thử lại (có FM active thì báo như thường);
-- mọi ADMIN đang hoạt động được báo MỘT lần cho mỗi sự cố, mốc ghi ở admin_no_fm_notified_at
-- (đặt bằng conditional UPDATE ... WHERE admin_no_fm_notified_at IS NULL để không báo lặp).

-- 1. Mốc đã báo ADMIN "không có FM"
ALTER TABLE incident_escalations
    ADD COLUMN admin_no_fm_notified_at TIMESTAMPTZ NULL;

-- 2. INCIDENT_ESCALATION_NO_FM: ADMIN nhận khi sự cố cần leo thang mà không có FM nào đang hoạt động.
-- Danh sách cũ lấy nguyên từ V80 (26 loại), không bỏ loại nào.
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
        'INCIDENT_ESCALATION_NO_FM'
    ));
