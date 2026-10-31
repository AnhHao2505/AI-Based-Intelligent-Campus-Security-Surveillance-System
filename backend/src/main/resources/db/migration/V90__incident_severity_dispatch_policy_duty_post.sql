-- ============================================================================
-- V90: phân phối sự cố PR-1 (K1, K2, K7, K10-a)
--   1. security_incidents.severity (BR-SEV-01): sự cố cũ để NULL, sự cố mới gán lúc ingest
--   2. incident_severity_rules + seed (BR-SEV-01..04). Không seed MOVING_OBJECT; ô không có luật dùng
--      INCIDENT_SEVERITY_DEFAULT (BR-SEV-03)
--   3. incident_dispatch_policies + seed theo mức (BR-DSP-12). PR-1 chỉ có bảng và màn admin, job chưa đọc
--   4. duty_post_types + seed, guard_shifts.duty_post_code mặc định PATROL (BR-DSP-13). Ca cũ và ca sinh tự động = PATROL
--   5. 4 khoá cấu hình nhóm SECURITY (display_order 59–62; 1, 40–58 đã dùng)
--   6. audit_event_types cho 3 danh mục mới (module SYSTEM, ADMIN đọc được)
-- Chỉ thêm: không đổi dữ liệu hay hành vi đang có.
-- ============================================================================

-- 1. Severity của sự cố
ALTER TABLE security_incidents ADD COLUMN IF NOT EXISTS severity VARCHAR(10);
ALTER TABLE security_incidents ADD CONSTRAINT chk_security_incidents_severity
    CHECK (severity IS NULL OR severity IN ('MEDIUM', 'HIGH', 'CRITICAL'));

-- 2. Luật severity: loại sự cố × loại khu (tên enum AreaLevel) × khung giờ
CREATE TABLE incident_severity_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50) NOT NULL,
    area_level_code VARCHAR(30) NOT NULL,
    time_window     VARCHAR(12) NOT NULL,
    severity        VARCHAR(10) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID REFERENCES users(id),
    CONSTRAINT uq_incident_severity_rules UNIQUE (event_type, area_level_code, time_window),
    CONSTRAINT chk_incident_severity_rules_area_level
        CHECK (area_level_code IN ('PUBLIC', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')),
    CONSTRAINT chk_incident_severity_rules_time_window
        CHECK (time_window IN ('IN_HOURS', 'AFTER_HOURS', 'ANY')),
    CONSTRAINT chk_incident_severity_rules_severity
        CHECK (severity IN ('MEDIUM', 'HIGH', 'CRITICAL'))
);

INSERT INTO incident_severity_rules (event_type, area_level_code, time_window, severity) VALUES
    ('UNKNOWN_PERSON',      'CONFIDENTIAL',        'IN_HOURS',    'HIGH'),
    ('UNKNOWN_PERSON',      'HIGHLY_CONFIDENTIAL', 'ANY',         'CRITICAL'),
    ('INTRUSION',           'PUBLIC',              'AFTER_HOURS', 'MEDIUM'),
    ('INTRUSION',           'CONFIDENTIAL',        'AFTER_HOURS', 'CRITICAL'),
    ('INTRUSION',           'HIGHLY_CONFIDENTIAL', 'ANY',         'CRITICAL'),
    ('UNAUTHORIZED_ACCESS', 'CONFIDENTIAL',        'ANY',         'HIGH'),
    ('UNAUTHORIZED_ACCESS', 'HIGHLY_CONFIDENTIAL', 'ANY',         'CRITICAL'),
    ('ACCESS_OUT_OF_TIME',  'CONFIDENTIAL',        'IN_HOURS',    'MEDIUM'),
    ('ACCESS_OUT_OF_TIME',  'CONFIDENTIAL',        'AFTER_HOURS', 'HIGH'),
    ('ACCESS_OUT_OF_TIME',  'HIGHLY_CONFIDENTIAL', 'ANY',         'CRITICAL')
ON CONFLICT (event_type, area_level_code, time_window) DO NOTHING;

-- 3. Chính sách dispatch theo mức: step1_size 0 = broadcast ngay
CREATE TABLE incident_dispatch_policies (
    severity               VARCHAR(10) PRIMARY KEY,
    step1_size             SMALLINT    NOT NULL,
    step1_timeout_seconds  INTEGER     NOT NULL,
    escalate_fm_minutes    INTEGER     NOT NULL,
    notify_fm_immediately  BOOLEAN     NOT NULL DEFAULT FALSE,
    version                BIGINT      NOT NULL DEFAULT 0,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by             UUID REFERENCES users(id),
    CONSTRAINT chk_incident_dispatch_policies_severity CHECK (severity IN ('MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_incident_dispatch_policies_step1_size CHECK (step1_size BETWEEN 0 AND 10),
    CONSTRAINT chk_incident_dispatch_policies_step1_timeout CHECK (step1_timeout_seconds BETWEEN 10 AND 600),
    CONSTRAINT chk_incident_dispatch_policies_escalate CHECK (escalate_fm_minutes BETWEEN 1 AND 60)
);

INSERT INTO incident_dispatch_policies (severity, step1_size, step1_timeout_seconds, escalate_fm_minutes, notify_fm_immediately) VALUES
    ('MEDIUM',   1, 60, 15, FALSE),
    ('HIGH',     2, 30,  5, FALSE),
    ('CRITICAL', 0, 10,  2, TRUE)
ON CONFLICT (severity) DO NOTHING;

-- 4. Danh mục vị trí trực và vị trí của từng ca
CREATE TABLE duty_post_types (
    code         VARCHAR(30)  PRIMARY KEY,
    label        VARCHAR(100) NOT NULL,
    dispatchable BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   UUID REFERENCES users(id)
);

INSERT INTO duty_post_types (code, label, dispatchable, is_active, sort_order) VALUES
    ('PATROL',      'Tuần tra',     TRUE,  TRUE, 1),
    ('GATE',        'Trực cổng',    FALSE, TRUE, 2),
    ('CAMERA_ROOM', 'Phòng camera', FALSE, TRUE, 3)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE guard_shifts ADD COLUMN IF NOT EXISTS duty_post_code VARCHAR(30) NOT NULL DEFAULT 'PATROL';
ALTER TABLE guard_shifts ADD CONSTRAINT fk_guard_shifts_duty_post
    FOREIGN KEY (duty_post_code) REFERENCES duty_post_types(code) ON DELETE RESTRICT;

-- 5. Khoá cấu hình (K7). INCIDENT_SEVERITY_DEFAULT có nhánh validate riêng trong SystemConfigService
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
    ('DISPATCH_ENABLED', 'false', 'BOOLEAN', NULL, NULL, NULL, 'SECURITY', 59,
     '[Phân phối sự cố] Bật mời bảo vệ theo đợt trước khi phát cảnh báo toàn bộ. Tắt thì sự cố được phát cảnh báo như hiện tại', true),
    ('DISPATCH_LOAD_WINDOW_MINUTES', '60', 'INTEGER', 5, 720, 'phút', 'SECURITY', 60,
     '[Phân phối sự cố] Khoảng thời gian tính số sự cố bảo vệ đã tiếp nhận (load score) khi xếp thứ tự mời', true),
    ('DISPATCH_CHECK_SECONDS', '5', 'INTEGER', 2, 30, 'giây', 'SECURITY', 61,
     '[Phân phối sự cố] Chu kỳ kiểm tra lời mời hết hạn và chuyển sự cố sang phát cảnh báo toàn bộ', true),
    ('INCIDENT_SEVERITY_DEFAULT', 'MEDIUM', 'STRING', NULL, NULL, NULL, 'SECURITY', 62,
     '[Phân phối sự cố] Mức độ gán cho sự cố khi không có luật severity khớp (MEDIUM, HIGH hoặc CRITICAL)', true)
ON CONFLICT (config_key) DO NOTHING;

-- 6. Audit: audit_logs có FK (target_type, action) -> audit_event_types
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('INCIDENT_SEVERITY_RULE', 'UPDATE', 'SYSTEM', 'Cập nhật luật mức độ sự cố'),
    ('INCIDENT_DISPATCH_POLICY', 'UPDATE', 'SYSTEM', 'Cập nhật chính sách phân phối sự cố'),
    ('DUTY_POST_TYPE', 'UPDATE', 'SYSTEM', 'Cập nhật danh mục vị trí trực')
ON CONFLICT (target_type, action) DO NOTHING;
