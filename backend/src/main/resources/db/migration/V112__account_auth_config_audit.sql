-- ============================================================================
-- V112 (PR-AUDIT): nhật ký kiểm toán cho tài khoản, đăng nhập và cấu hình hệ thống
-- ============================================================================
-- 1. IP và user agent của request đã gây ra thao tác (NULL với job hệ thống và mọi dòng cũ).
--    Trigger trg_audit_logs_append_only (V55) là BEFORE UPDATE OR DELETE: INSERT có cột mới vẫn được phép,
--    UPDATE / DELETE (kể cả trên cột mới) vẫn bị chặn. Không sửa dòng cũ.
ALTER TABLE audit_logs ADD COLUMN ip_address VARCHAR(45) NULL;
ALTER TABLE audit_logs ADD COLUMN user_agent VARCHAR(255) NULL;

-- 2. Cặp (target_type, action) mới. Từ V55 audit_logs không còn CHECK chk_audit_target_type / chk_audit_action;
--    ràng buộc thay thế là FK (target_type, action) -> audit_event_types, nên khai báo đủ cặp trước khi ghi.
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    -- ACCOUNT
    ('USER_ACCOUNT', 'CREATE', 'ACCOUNT', 'Tạo tài khoản'),
    ('USER_ACCOUNT', 'DEACTIVATE', 'ACCOUNT', 'Vô hiệu hoá tài khoản'),
    ('USER_ACCOUNT', 'REACTIVATE', 'ACCOUNT', 'Kích hoạt lại tài khoản'),
    ('USER_ACCOUNT', 'DELETE', 'ACCOUNT', 'Xoá tài khoản'),
    ('USER_IMPORT_BATCH', 'IMPORT', 'ACCOUNT', 'Nạp tài khoản theo lô'),
    ('USER_IMPORT_BATCH', 'DELETE', 'ACCOUNT', 'Gỡ lô tài khoản'),
    ('USER_IMPORT_BATCH', 'RESTORE', 'ACCOUNT', 'Khôi phục lô tài khoản'),
    ('FACE_REGISTRATION', 'REGISTER_FACE', 'ACCOUNT', 'Đăng ký / thay khuôn mặt'),
    -- AUTH
    ('AUTH', 'LOGIN_SUCCESS', 'AUTH', 'Đăng nhập thành công'),
    ('AUTH', 'LOGIN_FAILED', 'AUTH', 'Đăng nhập thất bại'),
    ('AUTH', 'ACCOUNT_LOCKED', 'AUTH', 'Khoá đăng nhập tạm thời'),
    ('AUTH', 'PASSWORD_RESET_REQUESTED', 'AUTH', 'Yêu cầu đặt lại mật khẩu'),
    ('AUTH', 'PASSWORD_RESET_COMPLETED', 'AUTH', 'Đặt lại mật khẩu thành công'),
    -- SYSTEM_CONFIG
    ('SYSTEM_CONFIG', 'UPDATE', 'SYSTEM_CONFIG', 'Sửa cấu hình hệ thống')
ON CONFLICT (target_type, action) DO NOTHING;

-- 3. Chỉ ADMIN đọc ba module mới (FACILITY_MANAGER không có dòng -> AuditService.getAuditLogs từ chối / lọc bỏ)
INSERT INTO audit_module_roles (module, role) VALUES
    ('ACCOUNT', 'ADMIN'),
    ('AUTH', 'ADMIN'),
    ('SYSTEM_CONFIG', 'ADMIN')
ON CONFLICT (module, role) DO NOTHING;

-- 4. Lọc nhật ký theo đối tượng (mã tài khoản / mã lô / e-mail đăng nhập). Lọc theo người bị tác động đã có
--    idx_audit_logs_subject_changed_at (V42).
CREATE INDEX IF NOT EXISTS idx_audit_logs_target_id ON audit_logs (target_id);
