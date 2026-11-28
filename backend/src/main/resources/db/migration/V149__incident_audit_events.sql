-- ============================================================================
-- V149: Nhật ký kiểm toán cho các thao tác thủ công trên sự cố an ninh
-- ============================================================================
-- Khai báo cặp (target_type, action) trong audit_event_types (FK từ audit_logs)
-- và quyền đọc module INCIDENT cho ADMIN và FACILITY_MANAGER trong audit_module_roles.

INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('SECURITY_INCIDENT', 'CLAIM', 'INCIDENT', 'Tiếp nhận xử lý sự cố an ninh'),
    ('SECURITY_INCIDENT', 'DECLINE', 'INCIDENT', 'Từ chối lời mời xử lý sự cố an ninh'),
    ('SECURITY_INCIDENT', 'RESOLVE', 'INCIDENT', 'Xử lý và đóng sự cố an ninh')
ON CONFLICT (target_type, action) DO NOTHING;

INSERT INTO audit_module_roles (module, role) VALUES
    ('INCIDENT', 'ADMIN'),
    ('INCIDENT', 'FACILITY_MANAGER')
ON CONFLICT (module, role) DO NOTHING;
