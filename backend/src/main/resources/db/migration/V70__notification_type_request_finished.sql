-- ============================================================================
-- V70: B-03 (BR-RQ-46) — loại thông báo khi FM chuyển đơn truy cập sang Hoàn thành
-- ============================================================================
-- REQUEST_FINISHED : người tạo đơn + thành viên nhóm, kèm lý do FM nhập
-- Danh sách cũ lấy nguyên từ V63 (24 loại), không bỏ loại nào.
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
        'REQUEST_FINISHED'
    ));
