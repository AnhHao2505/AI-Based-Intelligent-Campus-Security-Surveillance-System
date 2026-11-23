-- ============================================================================
-- V142: Nhật ký kiểm toán cho thao tác camera & ngắt kết nối stream
-- ============================================================================
-- Khai báo cặp (target_type, action) trong audit_event_types (FK từ audit_logs)
-- và quyền đọc module CAMERA cho ADMIN trong audit_module_roles.

INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('CAMERA', 'STOP_STREAM', 'CAMERA', 'Ngắt kết nối stream'),
    ('CAMERA', 'DEACTIVATE', 'CAMERA', 'Tắt camera'),
    ('CAMERA', 'DELETE', 'CAMERA', 'Xóa camera')
ON CONFLICT (target_type, action) DO NOTHING;

INSERT INTO audit_module_roles (module, role) VALUES
    ('CAMERA', 'ADMIN')
ON CONFLICT (module, role) DO NOTHING;
