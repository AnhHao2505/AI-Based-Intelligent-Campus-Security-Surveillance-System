-- ============================================================================
-- V135: Sự kiện audit "đặt cấp truy cập ban đầu" khi Admin tạo tay tài khoản (BR-AL-33)
--
-- Bối cảnh:
--   - UserService.createStaffAccount đã ghi CREATE / USER_ACCOUNT (module ACCOUNT, V112) có accessLevel trong snapshot,
--     nhưng module ACCOUNT chỉ ADMIN đọc được (audit_module_roles). FM là người đổi cấp về sau nên cần tra được cấp ban đầu.
--   - Ghi thêm một dòng USER_ACCESS_LEVEL / CREATE (module ACCESS_CONTROL, ADMIN và FACILITY_MANAGER đều đọc được từ V55),
--     cùng dạng với USER_ACCESS_LEVEL / UPDATE: target_id = subject_user_id = id tài khoản, new_value = {"accessLevel": n},
--     old_value NULL, changed_by = Admin tạo tài khoản.
--
-- Ràng buộc:
--   - Chỉ thêm cặp (target_type, action) cho FK fk_audit_logs_event_type. Không đổi audit_logs, users, system_configurations.
--   - Chạy lại không lỗi (ON CONFLICT DO NOTHING).
-- ============================================================================

INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('USER_ACCESS_LEVEL', 'CREATE', 'ACCESS_CONTROL', 'Đặt cấp truy cập ban đầu khi tạo tài khoản')
ON CONFLICT (target_type, action) DO NOTHING;
