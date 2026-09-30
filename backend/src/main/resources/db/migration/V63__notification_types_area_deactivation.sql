-- ============================================================================
-- V63: Step 6 follow-up (H1, H2) — 2 loại thông báo khi vô hiệu hoá khu vực
-- ============================================================================
-- ACCESS_PERMISSION_REVOKED        : người được gán của AP bị thu hồi (BR-AD-04)
-- GUEST_VISIT_CANCELLED_BY_SYSTEM  : host của lượt khách PENDING bị huỷ (BR-AD-09)
-- Danh sách cũ lấy nguyên từ định nghĩa CHECK hiện tại trên DB test (22 loại, V60), không bỏ loại nào.
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
        'GUEST_VISIT_CANCELLED_BY_SYSTEM'
    ));
