-- ============================================================================
-- V111 (PR-AU): loại thông báo ACCOUNT_LOCKED
-- ============================================================================
-- Mọi ADMIN đang hoạt động được báo một lần mỗi đợt khoá khi một tài khoản có thật bị khoá đăng nhập tạm thời
-- vì sai mật khẩu quá LOGIN_MAX_FAILED_ATTEMPTS lần (V110). reference_id = id tài khoản, reference_type = 'USER'.
-- Danh sách cũ lấy nguyên từ V84 (27 loại), không bỏ loại nào.
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
        'ACCOUNT_LOCKED'
    ));
