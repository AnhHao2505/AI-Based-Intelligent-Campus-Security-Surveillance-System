-- ============================================================================
-- V55_1: Scheduled Event Mode, Notification Types, and Audit Event Types
-- ============================================================================

-- 1. Create table area_event_schedules
CREATE TABLE area_event_schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    area_id UUID NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(15) NOT NULL,
    reason_code VARCHAR(50),
    reason_label VARCHAR(255),
    note VARCHAR(500),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NULL,
    updated_at TIMESTAMPTZ NULL,
    cancelled_by UUID NULL,
    cancelled_at TIMESTAMPTZ NULL,
    cancel_reason_code VARCHAR(50),
    cancel_reason_label VARCHAR(255),
    cancel_note VARCHAR(500),
    failed_at TIMESTAMPTZ NULL,
    fail_reason VARCHAR(255),
    session_id UUID NULL,
    reminded_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_area_event_schedules_area FOREIGN KEY (area_id) REFERENCES areas(id) ON DELETE RESTRICT,
    CONSTRAINT fk_area_event_schedules_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_area_event_schedules_updated_by FOREIGN KEY (updated_by) REFERENCES users(id),
    CONSTRAINT fk_area_event_schedules_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users(id),
    CONSTRAINT fk_area_event_schedules_session FOREIGN KEY (session_id) REFERENCES area_event_sessions(id),
    CONSTRAINT chk_area_event_schedules_time CHECK (end_at > start_at),
    CONSTRAINT chk_area_event_schedules_status CHECK (status IN ('SCHEDULED', 'STARTED', 'CANCELLED', 'FAILED'))
);

CREATE INDEX idx_area_event_schedules_area_status_start ON area_event_schedules (area_id, status, start_at);
CREATE INDEX idx_area_event_schedules_status_start ON area_event_schedules (status, start_at);

-- 2. Seed system configurations for event mode scheduling
INSERT INTO system_configurations (config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable)
VALUES
    ('EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS', '30', 'INTEGER', 1, 180, 'ngày', 'SECURITY', 46, '[Chế độ sự kiện] Số ngày tối đa được phép đặt lịch sự kiện trước (ngày)', true),
    ('EVENT_MODE_MAX_SCHEDULES_PER_AREA', '5', 'INTEGER', 1, 20, 'lịch', 'SECURITY', 47, '[Chế độ sự kiện] Số lượng lịch sự kiện chờ diễn ra tối đa cho mỗi khu vực', true),
    ('EVENT_MODE_SCHEDULE_REMINDER_MINUTES', '30', 'INTEGER', 0, 1440, 'phút', 'SECURITY', 48, '[Chế độ sự kiện] Thời gian nhắc người quản lý trước khi lịch sự kiện bắt đầu (phút, 0 = không nhắc)', true)
ON CONFLICT (config_key) DO NOTHING;

-- 3. Extend notifications type check constraint
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
        'EVENT_MODE_SCHEDULE_FAILED'
    ));

-- 4. Seed audit_event_types
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('AREA_EVENT_SCHEDULE', 'CREATE', 'AREA', 'Đặt lịch chế độ sự kiện'),
    ('AREA_EVENT_SCHEDULE', 'UPDATE', 'AREA', 'Điều chỉnh lịch chế độ sự kiện'),
    ('AREA_EVENT_SCHEDULE', 'CANCEL', 'AREA', 'Huỷ lịch chế độ sự kiện'),
    ('AREA_EVENT_SCHEDULE', 'FAIL', 'AREA', 'Kích hoạt lịch sự kiện thất bại'),
    ('AREA_EVENT_MODE', 'EXPIRE_EVENT_MODE', 'AREA', 'Hết hạn chế độ sự kiện')
ON CONFLICT (target_type, action) DO NOTHING;
