-- ============================================================================
-- V55: Generalize access control audit logs to system-wide audit logs
-- ============================================================================

-- 1. Rename table access_control_audit_logs to audit_logs
ALTER TABLE access_control_audit_logs RENAME TO audit_logs;

-- Rename trigger and trigger function
ALTER TRIGGER trg_access_control_audit_logs_append_only ON audit_logs RENAME TO trg_audit_logs_append_only;
ALTER FUNCTION trg_prevent_access_control_audit_logs_modification() RENAME TO trg_prevent_audit_logs_modification;

CREATE OR REPLACE FUNCTION trg_prevent_audit_logs_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: updates and deletes are prohibited';
END;
$$ LANGUAGE plpgsql;

-- 2. Create table audit_event_types and seed
CREATE TABLE audit_event_types (
    target_type VARCHAR(30) NOT NULL,
    action      VARCHAR(30) NOT NULL,
    module      VARCHAR(30) NOT NULL,
    description VARCHAR(255),
    PRIMARY KEY (target_type, action)
);

-- Pre-seed all predefined event types
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    -- AREA module
    ('AREA', 'CREATE', 'AREA', 'Tạo mới khu vực'),
    ('AREA', 'UPDATE', 'AREA', 'Cập nhật thông tin khu vực'),
    ('AREA', 'UPDATE_GEOMETRY', 'AREA', 'Cập nhật toạ độ/hình học khu vực'),
    ('AREA', 'DELETE_GEOMETRY', 'AREA', 'Xoá toạ độ/hình học khu vực'),
    ('AREA', 'DEACTIVATE', 'AREA', 'Vô hiệu hoá khu vực'),
    ('AREA', 'UPDATE_CAMERAS', 'AREA', 'Cập nhật danh sách camera gán vào khu vực'),
    ('AREA_EVENT_MODE', 'ENABLE_EVENT_MODE', 'AREA', 'Bật chế độ sự kiện'),
    ('AREA_EVENT_MODE', 'DISABLE_EVENT_MODE', 'AREA', 'Tắt chế độ sự kiện'),
    ('AREA_EVENT_MODE', 'EXTEND_EVENT_MODE', 'AREA', 'Gia hạn chế độ sự kiện'),

    -- ACCESS_CONTROL module
    ('AREA_ASSIGNMENT', 'ASSIGN', 'ACCESS_CONTROL', 'Gán nhân sự vào khu vực'),
    ('AREA_ASSIGNMENT', 'UPDATE_VALIDITY', 'ACCESS_CONTROL', 'Cập nhật thời hạn nhân sự khu vực'),
    ('AREA_ASSIGNMENT', 'REVOKE', 'ACCESS_CONTROL', 'Thu hồi quyền nhân sự khu vực'),
    ('USER_ACCESS_LEVEL', 'UPDATE', 'ACCESS_CONTROL', 'Cập nhật cấp độ truy cập người dùng'),
    ('AREA_ACCESS_RULES', 'UPDATE', 'ACCESS_CONTROL', 'Cập nhật quy tắc truy cập khu vực'),
    ('LEVEL_PRESET', 'UPDATE', 'ACCESS_CONTROL', 'Cập nhật cấu hình cấp độ truy cập mặc định'),

    -- ACCESS_REQUEST module
    ('ACCESS_REQUEST', 'CREATE', 'ACCESS_REQUEST', 'Tạo yêu cầu truy cập'),
    ('ACCESS_REQUEST', 'APPROVE', 'ACCESS_REQUEST', 'Phê duyệt yêu cầu truy cập'),
    ('ACCESS_REQUEST', 'REJECT', 'ACCESS_REQUEST', 'Từ chối yêu cầu truy cập'),
    ('ACCESS_REQUEST', 'CANCEL', 'ACCESS_REQUEST', 'Huỷ yêu cầu truy cập'),
    ('ACCESS_REQUEST', 'FINISH', 'ACCESS_REQUEST', 'Hoàn thành yêu cầu truy cập'),
    ('ACCESS_REQUEST', 'EXPIRE', 'ACCESS_REQUEST', 'Hết hạn yêu cầu truy cập'),

    -- SYSTEM module
    ('REASON_CATALOG', 'CREATE', 'SYSTEM', 'Tạo lý do sự kiện mới'),
    ('REASON_CATALOG', 'UPDATE', 'SYSTEM', 'Cập nhật lý do sự kiện'),
    ('REASON_CATALOG', 'DEACTIVATE', 'SYSTEM', 'Vô hiệu hoá lý do sự kiện'),
    ('REASON_CATALOG', 'REACTIVATE', 'SYSTEM', 'Kích hoạt lại lý do sự kiện')
ON CONFLICT (target_type, action) DO NOTHING;

-- Seed any distinct pairs existing in audit_logs that may not be in the initial list
INSERT INTO audit_event_types (target_type, action, module, description)
SELECT DISTINCT target_type, action,
    CASE
        WHEN target_type IN ('AREA', 'AREA_EVENT_MODE') THEN 'AREA'
        WHEN target_type IN ('AREA_ASSIGNMENT', 'USER_ACCESS_LEVEL', 'AREA_ACCESS_RULES', 'LEVEL_PRESET') THEN 'ACCESS_CONTROL'
        WHEN target_type = 'ACCESS_REQUEST' THEN 'ACCESS_REQUEST'
        WHEN target_type = 'REASON_CATALOG' THEN 'SYSTEM'
        ELSE 'SYSTEM'
    END,
    'Tự động đồng bộ từ dữ liệu cũ'
FROM audit_logs
ON CONFLICT (target_type, action) DO NOTHING;

-- Drop old check constraints and add Foreign Key to audit_event_types
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS chk_audit_target_type;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS chk_audit_action;
ALTER TABLE audit_logs ADD CONSTRAINT fk_audit_logs_event_type
    FOREIGN KEY (target_type, action) REFERENCES audit_event_types (target_type, action) ON DELETE RESTRICT;

-- 3. Create table audit_module_roles and seed
CREATE TABLE audit_module_roles (
    module VARCHAR(30) NOT NULL,
    role   VARCHAR(30) NOT NULL,
    PRIMARY KEY (module, role)
);

INSERT INTO audit_module_roles (module, role) VALUES
    ('AREA', 'ADMIN'),
    ('ACCESS_CONTROL', 'ADMIN'),
    ('ACCESS_REQUEST', 'ADMIN'),
    ('SYSTEM', 'ADMIN'),
    ('AREA', 'FACILITY_MANAGER'),
    ('ACCESS_CONTROL', 'FACILITY_MANAGER'),
    ('ACCESS_REQUEST', 'FACILITY_MANAGER')
ON CONFLICT (module, role) DO NOTHING;

-- 4. Actor support: SYSTEM and USER
ALTER TABLE audit_logs ALTER COLUMN changed_by DROP NOT NULL;
ALTER TABLE audit_logs ADD COLUMN actor_type VARCHAR(10) NOT NULL DEFAULT 'USER';
ALTER TABLE audit_logs ALTER COLUMN actor_type DROP DEFAULT;
ALTER TABLE audit_logs ADD COLUMN actor_source VARCHAR(100) NULL;

ALTER TABLE audit_logs ADD CONSTRAINT chk_audit_actor CHECK (
    (actor_type = 'USER' AND changed_by IS NOT NULL AND actor_source IS NULL) OR
    (actor_type = 'SYSTEM' AND changed_by IS NULL AND actor_source IS NOT NULL)
);

-- 5. Correlation ID support
ALTER TABLE audit_logs ADD COLUMN correlation_id UUID NULL;
ALTER TABLE audit_logs ADD CONSTRAINT chk_audit_correlation CHECK (correlation_id IS NOT NULL) NOT VALID;

-- 6. Indexes
CREATE INDEX IF NOT EXISTS idx_audit_logs_changed_at_desc ON audit_logs (changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_target_action_time ON audit_logs (target_type, action, changed_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_correlation_id ON audit_logs (correlation_id);

-- 7. System configuration change logs reason column
ALTER TABLE system_configuration_change_logs ADD COLUMN IF NOT EXISTS reason VARCHAR(500) NULL;
