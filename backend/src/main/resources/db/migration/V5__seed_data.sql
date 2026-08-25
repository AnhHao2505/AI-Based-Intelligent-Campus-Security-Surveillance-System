-- ============================================================================
-- V5: Seed Data — Dữ liệu cấu hình khởi tạo bắt buộc
-- ============================================================================

-- ============================================================================
-- severity_rules: 9 dòng (3 violation_type × 3 area_level)
-- Bảng tra cứu: khi có sự cố, Backend tra violation_type + area_level → severity
-- ============================================================================

INSERT INTO severity_rules (violation_type, area_level, severity) VALUES
    -- UNAUTHORIZED_ACCESS: người có quyền nhưng hết hạn/không đúng khu vực
    ('UNAUTHORIZED_ACCESS', 1, 'LOW'),       -- Public area: mức thấp
    ('UNAUTHORIZED_ACCESS', 2, 'MEDIUM'),    -- Semi-Private: mức trung bình
    ('UNAUTHORIZED_ACCESS', 3, 'HIGH'),      -- Private: mức cao

    -- UNKNOWN_PERSON: không nhận diện được khuôn mặt (không có trong DB)
    ('UNKNOWN_PERSON', 1, 'LOW'),
    ('UNKNOWN_PERSON', 2, 'MEDIUM'),
    ('UNKNOWN_PERSON', 3, 'HIGH'),

    -- LOITERING: lảng vảng bất thường (phát hiện qua tracking)
    ('LOITERING', 1, 'LOW'),
    ('LOITERING', 2, 'LOW'),                -- Semi-Private vẫn LOW cho LOITERING
    ('LOITERING', 3, 'MEDIUM');              -- Private mới nâng MEDIUM

-- ============================================================================
-- system_config: 2 dòng cấu hình hệ thống
-- ============================================================================

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('default_severity', 'MEDIUM',
     'Severity mặc định khi không tìm thấy rule khớp (area_level > 3). Backend fallback về giá trị này.'),
    ('default_escalation_target', 'NOT_SET',
     'UUID của Facility Manager mặc định nhận escalation. Cần cấu hình trước khi MF3 hoạt động. Backend phải handle graceful khi giá trị = NOT_SET (log cảnh báo, không crash).');
