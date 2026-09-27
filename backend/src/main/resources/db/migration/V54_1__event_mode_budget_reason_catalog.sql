-- V54_1: Event Mode Budget, Reason Catalog, and Audit Extensions

-- 1. Extend audit log constraints
ALTER TABLE access_control_audit_logs
    DROP CONSTRAINT chk_audit_target_type,
    ADD CONSTRAINT chk_audit_target_type CHECK (target_type IN ('AREA_ASSIGNMENT', 'USER_ACCESS_LEVEL', 'AREA_ACCESS_RULES', 'LEVEL_PRESET', 'AREA_EVENT_MODE', 'REASON_CATALOG'));

ALTER TABLE access_control_audit_logs
    DROP CONSTRAINT chk_audit_action,
    ADD CONSTRAINT chk_audit_action CHECK (action IN ('ASSIGN', 'UPDATE_VALIDITY', 'REVOKE', 'UPDATE', 'ENABLE_EVENT_MODE', 'DISABLE_EVENT_MODE', 'EXTEND_EVENT_MODE', 'CREATE', 'DEACTIVATE', 'REACTIVATE'));

-- 2. Reason catalog table
CREATE TABLE reason_catalog (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(30) NOT NULL,
    code VARCHAR(50) NOT NULL,
    label VARCHAR(255) NOT NULL,
    is_other BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_reason_catalog_action_type CHECK (action_type IN ('EVENT_ENABLE', 'EVENT_DISABLE', 'EVENT_EXTEND')),
    CONSTRAINT uq_reason_catalog_action_code UNIQUE (action_type, code)
);

CREATE UNIQUE INDEX ux_reason_catalog_other_per_action ON reason_catalog (action_type) WHERE is_other = true;

-- Seed reason catalog
INSERT INTO reason_catalog (action_type, code, label, is_other, is_active, sort_order) VALUES
    ('EVENT_ENABLE', 'SEMINAR', 'Hội thảo/sự kiện chuyên môn', false, true, 10),
    ('EVENT_ENABLE', 'VISIT', 'Tham quan/tuyển sinh', false, true, 20),
    ('EVENT_ENABLE', 'EXTENDED_MEETING', 'Họp mở rộng', false, true, 30),
    ('EVENT_ENABLE', 'OTHER', 'Khác', true, true, 999),

    ('EVENT_DISABLE', 'ENDED_EARLY', 'Kết thúc sớm', false, true, 10),
    ('EVENT_DISABLE', 'SECURITY_INCIDENT', 'Sự cố an ninh', false, true, 20),
    ('EVENT_DISABLE', 'MISTAKE', 'Thao tác nhầm', false, true, 30),
    ('EVENT_DISABLE', 'OTHER', 'Khác', true, true, 999),

    ('EVENT_EXTEND', 'EVENT_PROLONGED', 'Sự kiện kéo dài', false, true, 10),
    ('EVENT_EXTEND', 'OTHER', 'Khác', true, true, 999);

-- 3. Area event sessions table
CREATE TABLE area_event_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    area_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    planned_end TIMESTAMPTZ NOT NULL,
    actual_end TIMESTAMPTZ NULL,
    started_by UUID NOT NULL,
    ended_by UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_sessions_area FOREIGN KEY (area_id) REFERENCES areas(id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_sessions_started_by FOREIGN KEY (started_by) REFERENCES users(id),
    CONSTRAINT fk_event_sessions_ended_by FOREIGN KEY (ended_by) REFERENCES users(id),
    CONSTRAINT chk_event_sessions_planned_end CHECK (planned_end > started_at),
    CONSTRAINT chk_event_sessions_actual_end CHECK (actual_end IS NULL OR (actual_end >= started_at AND actual_end <= planned_end))
);

CREATE UNIQUE INDEX ux_area_event_sessions_active ON area_event_sessions (area_id) WHERE actual_end IS NULL;
CREATE INDEX idx_area_event_sessions_area_time ON area_event_sessions (area_id, started_at, actual_end, planned_end);

-- 4. Seed system configurations for event mode
INSERT INTO system_configurations (config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable)
VALUES
    ('EVENT_MODE_MAX_HOURS', '12', 'INTEGER', 1, 72, 'giờ', 'SECURITY', 40, 'Thời lượng tối đa cho một phiên mở chế độ sự kiện (giờ)', true),
    ('EVENT_MODE_WINDOW_DAYS', '7', 'INTEGER', 1, 30, 'ngày', 'SECURITY', 41, 'Khung thời gian xét ngân sách sự kiện (ngày)', true),
    ('EVENT_MODE_BUDGET_HOURS', '48', 'INTEGER', 1, 720, 'giờ', 'SECURITY', 42, 'Ngân sách thời lượng mở sự kiện tối đa trong khung thời gian (giờ)', true)
ON CONFLICT (config_key) DO NOTHING;

-- 5. Extend notifications type constraint
ALTER TABLE notifications
    DROP CONSTRAINT chk_notifications_type,
    ADD CONSTRAINT chk_notifications_type CHECK (type IN ('REQUEST_APPROVED', 'REQUEST_REJECTED', 'EXPIRING_SOON', 'ACCESS_DENIED', 'ADDED_TO_GROUP', 'NEW_REQUEST_PENDING', 'REQUEST_CANCELLED', 'PENDING_OVERDUE', 'EVENT_MODE_LIMIT_CHANGED'));
