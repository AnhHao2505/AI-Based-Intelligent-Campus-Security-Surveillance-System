-- ============================================================================
-- V144: Nhật ký kiểm toán cho đội bảo vệ, điều động tăng cường, ca trực và đơn đổi ca/nghỉ phép
-- ============================================================================
-- Khai báo các cặp (target_type, action) trong audit_event_types (FK từ audit_logs)
-- và quyền đọc module GUARD cho ADMIN và FACILITY_MANAGER trong audit_module_roles.

INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    -- Tổ đội bảo vệ
    ('GUARD_TEAM', 'CREATE', 'GUARD', 'Tạo tổ đội bảo vệ'),
    ('GUARD_TEAM', 'REACTIVATE', 'GUARD', 'Tái kích hoạt tổ đội bảo vệ'),
    ('GUARD_TEAM', 'UPDATE', 'GUARD', 'Cập nhật tổ đội bảo vệ'),
    ('GUARD_TEAM', 'DELETE', 'GUARD', 'Xóa tổ đội bảo vệ'),
    ('GUARD_TEAM', 'ASSIGN', 'GUARD', 'Phân công thành viên tổ đội bảo vệ'),
    -- Điều động tăng cường
    ('GUARD_TEAM_DISPATCH', 'CREATE', 'GUARD', 'Điều động tăng cường bảo vệ'),
    ('GUARD_TEAM_DISPATCH', 'CANCEL', 'GUARD', 'Hủy điều động tăng cường bảo vệ'),
    ('GUARD_TEAM_DISPATCH', 'EXPIRE', 'GUARD', 'Tự động hoàn tất điều động tăng cường'),
    -- Ca trực bảo vệ
    ('GUARD_SHIFT', 'CREATE', 'GUARD', 'Tạo ca trực bảo vệ'),
    ('GUARD_SHIFT', 'UPDATE', 'GUARD', 'Cập nhật ca trực bảo vệ'),
    ('GUARD_SHIFT', 'DELETE', 'GUARD', 'Xóa ca trực bảo vệ'),
    ('GUARD_SHIFT', 'CHECK_IN', 'GUARD', 'Điểm danh nhận ca trực'),
    ('GUARD_SHIFT', 'CHECK_OUT', 'GUARD', 'Điểm danh kết thúc ca trực'),
    ('GUARD_SHIFT', 'MARK_ABSENT', 'GUARD', 'Ghi nhận vắng mặt ca trực'),
    ('GUARD_SHIFT', 'CANCEL', 'GUARD', 'Hủy ca trực'),
    -- Yêu cầu đổi ca / nghỉ phép
    ('GUARD_SHIFT_REQUEST', 'CREATE', 'GUARD', 'Tạo yêu cầu đổi ca / nghỉ phép'),
    ('GUARD_SHIFT_REQUEST', 'APPROVE', 'GUARD', 'Phê duyệt yêu cầu đổi ca / nghỉ phép'),
    ('GUARD_SHIFT_REQUEST', 'REJECT', 'GUARD', 'Từ chối yêu cầu đổi ca / nghỉ phép')
ON CONFLICT (target_type, action) DO NOTHING;

INSERT INTO audit_module_roles (module, role) VALUES
    ('GUARD', 'ADMIN'),
    ('GUARD', 'FACILITY_MANAGER')
ON CONFLICT (module, role) DO NOTHING;
