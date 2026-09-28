-- ============================================================================
-- V56: Step 5b — đổi loại khu vực, version khu vực, hệ thống huỷ đơn,
--      nhóm lý do cho lịch sự kiện, trạng thái lịch theo phiên.
-- Không đụng center_latitude / center_longitude (V59). Không phụ thuộc đối tượng do V58/V59 tạo,
-- nên chạy được out-of-order sau V58/V59.
-- ============================================================================

-- 1. areas.version (BR-TC-13): tăng đúng 1 mỗi lần dòng areas thay đổi thật
ALTER TABLE areas
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 2. notifications: thêm AREA_TYPE_CHANGED (BR-TC-10), REQUEST_SYSTEM_CANCELLED (BR-TC-16)
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
        'REQUEST_SYSTEM_CANCELLED'
    ));

-- 3. audit_event_types: đổi loại khu vực (BR-TC-02). Huỷ đơn bởi hệ thống dùng lại (ACCESS_REQUEST, CANCEL) đã có ở V55.
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('AREA', 'CHANGE_TYPE', 'AREA', 'Đổi loại khu vực')
ON CONFLICT (target_type, action) DO NOTHING;

-- 4. access_requests: nguồn huỷ đơn (BR-TC-15).
--    Đơn đã CANCELLED trước V56 giữ nguyên, cancel_source NULL = không rõ nguồn.
ALTER TABLE access_requests
    ADD COLUMN IF NOT EXISTS cancelled_by UUID NULL REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS cancel_source VARCHAR(10) NULL,
    ADD COLUMN IF NOT EXISTS cancel_reason TEXT NULL;

ALTER TABLE access_requests
    ADD CONSTRAINT chk_access_requests_cancel_source
        CHECK (cancel_source IS NULL OR cancel_source IN ('USER', 'SYSTEM')),
    ADD CONSTRAINT chk_access_requests_cancel_fields
        CHECK (status = 'CANCELLED' OR (cancel_source IS NULL AND cancelled_by IS NULL AND cancel_reason IS NULL)),
    ADD CONSTRAINT chk_access_requests_system_cancel
        CHECK (cancel_source IS DISTINCT FROM 'SYSTEM' OR cancelled_by IS NULL);

-- 5. reason_catalog: 3 nhóm lý do riêng cho lịch sự kiện (BR-ES-L1).
--    Giữ ux_reason_catalog_other_per_action: mỗi nhóm đúng một mục OTHER.
ALTER TABLE reason_catalog
    DROP CONSTRAINT IF EXISTS chk_reason_catalog_action_type,
    ADD CONSTRAINT chk_reason_catalog_action_type CHECK (action_type IN (
        'EVENT_ENABLE',
        'EVENT_DISABLE',
        'EVENT_EXTEND',
        'EVENT_SCHEDULE_CREATE',
        'EVENT_SCHEDULE_UPDATE',
        'EVENT_SCHEDULE_CANCEL'
    ));

INSERT INTO reason_catalog (action_type, code, label, is_other, is_active, sort_order) VALUES
    ('EVENT_SCHEDULE_CREATE', 'PLANNED_EVENT', 'Sự kiện theo kế hoạch', false, true, 10),
    ('EVENT_SCHEDULE_CREATE', 'SEMINAR', 'Hội thảo / seminar', false, true, 20),
    ('EVENT_SCHEDULE_CREATE', 'VISIT', 'Tham quan', false, true, 30),
    ('EVENT_SCHEDULE_CREATE', 'OTHER', 'Khác', true, true, 999),

    ('EVENT_SCHEDULE_UPDATE', 'ORGANIZER_CHANGED', 'Ban tổ chức đổi giờ', false, true, 10),
    ('EVENT_SCHEDULE_UPDATE', 'INPUT_ERROR', 'Nhập sai giờ', false, true, 20),
    ('EVENT_SCHEDULE_UPDATE', 'OTHER', 'Khác', true, true, 999),

    ('EVENT_SCHEDULE_CANCEL', 'EVENT_CANCELLED', 'Sự kiện bị huỷ', false, true, 10),
    ('EVENT_SCHEDULE_CANCEL', 'BOOKED_BY_MISTAKE', 'Đặt nhầm', false, true, 20),
    ('EVENT_SCHEDULE_CANCEL', 'SECURITY_REASON', 'Lý do an ninh', false, true, 30),
    ('EVENT_SCHEDULE_CANCEL', 'OTHER', 'Khác', true, true, 999)
ON CONFLICT (action_type, code) DO NOTHING;

-- 6. area_event_schedules: trạng thái theo phiên do lịch sinh ra (BR-ES-S1)
--    STARTED -> COMPLETED (phiên đóng do hết giờ) | ENDED_EARLY (FM tắt phiên đó)
ALTER TABLE area_event_schedules
    DROP CONSTRAINT IF EXISTS chk_area_event_schedules_status,
    ADD CONSTRAINT chk_area_event_schedules_status CHECK (status IN (
        'SCHEDULED', 'STARTED', 'CANCELLED', 'FAILED', 'COMPLETED', 'ENDED_EARLY'
    ));

-- 7. area_event_sessions.schedule_id: phiên -> lịch nguồn.
--    Phiên mới do ADJUST kéo dài kế thừa lịch của phiên trước, nên khi chuỗi phiên kết thúc vẫn biết lịch nào cần đổi trạng thái.
ALTER TABLE area_event_sessions
    ADD COLUMN IF NOT EXISTS schedule_id UUID NULL REFERENCES area_event_schedules(id);

CREATE INDEX IF NOT EXISTS idx_area_event_sessions_schedule ON area_event_sessions (schedule_id);

-- Nối phiên đầu tiên của các lịch đã kích hoạt trước V56 (liên kết cũ area_event_schedules.session_id)
UPDATE area_event_sessions s
SET schedule_id = sch.id
FROM area_event_schedules sch
WHERE sch.session_id = s.id
  AND s.schedule_id IS NULL;
