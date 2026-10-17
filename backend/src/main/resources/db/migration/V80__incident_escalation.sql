-- ============================================================================
-- V80: Leo thang sự cố an ninh (BR-IE-01..10)
-- ============================================================================
-- Sự cố mới -> gửi ngay kênh riêng của mọi bảo vệ đang trực, tạo 1 bản ghi incident_escalations.
-- Vẫn NEW sau INCIDENT_ESCALATE_FM_MINUTES (hoặc lúc tạo không có bảo vệ nào trực) -> báo mọi FM đang hoạt động.
-- Rời NEW trước hạn (tiếp nhận / xử lý xong) -> đóng bản ghi, không báo FM.
-- Job đọc INCIDENT_ESCALATION_CHECK_SECONDS trước mỗi lần chạy nên sửa có hiệu lực không cần restart.
-- Sự cố tạo trước V80 không có bản ghi -> không bao giờ được leo thang (BR-IE-03).

-- 1. Bản ghi leo thang, 1 dòng / sự cố
CREATE TABLE incident_escalations (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id           UUID NOT NULL UNIQUE REFERENCES security_incidents(id) ON DELETE CASCADE,
    guards_notified_at    TIMESTAMPTZ NOT NULL,
    guard_recipient_count INTEGER NOT NULL,
    fm_escalated_at       TIMESTAMPTZ NULL,
    fm_recipient_count    INTEGER NULL,
    closed_at             TIMESTAMPTZ NULL,
    close_reason          VARCHAR(30) NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_incident_escalations_close_reason
        CHECK (close_reason IN ('ESCALATED', 'CLAIMED', 'RESOLVED', 'DISMISSED'))
);

-- Job chỉ quét bản ghi còn mở
CREATE INDEX idx_incident_escalations_open
    ON incident_escalations(closed_at)
    WHERE closed_at IS NULL;

-- 2. Tham số leo thang, cùng nhóm SECURITY (display_order 40–53 đã dùng)
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
    ('INCIDENT_ESCALATE_FM_MINUTES', '5', 'INTEGER', 1, 60, 'phút', 'SECURITY', 54,
     '[Leo thang sự cố] Sự cố chưa được bảo vệ nào tiếp nhận sau bao nhiêu phút kể từ lúc tạo thì báo Quản lý cơ sở vật chất', true),
    ('INCIDENT_ESCALATION_CHECK_SECONDS', '30', 'INTEGER', 10, 120, 'giây', 'SECURITY', 55,
     '[Leo thang sự cố] Chu kỳ kiểm tra sự cố cần leo thang (giây); áp dụng từ lần kiểm tra kế tiếp, không cần khởi động lại', true)
ON CONFLICT (config_key) DO NOTHING;

-- 3. INCIDENT_ESCALATED: FM nhận khi sự cố quá hạn chưa ai tiếp nhận
-- Danh sách cũ lấy nguyên từ V70 (25 loại), không bỏ loại nào.
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
        'INCIDENT_ESCALATED'
    ));
