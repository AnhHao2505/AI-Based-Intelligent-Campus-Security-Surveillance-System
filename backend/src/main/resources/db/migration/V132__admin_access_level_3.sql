-- ============================================================================
-- V132: Quản trị viên (role ADMIN) luôn ở cấp truy cập 3
--
-- Bối cảnh:
--   - AD-001 được tạo ở V7 khi chưa có cột access_level; V38 thêm cột mặc định 1 và chỉ nâng GUARD /
--     FACILITY_MANAGER lên 2; V83 chỉ đổi cấu hình ACCESS_LEVEL_DEFAULT_ADMIN = 3 cho tài khoản tạo mới,
--     không UPDATE users. Mọi DB dựng mới từ Flyway vì thế có AD-001 ở cấp 1.
--   - Nghiệp vụ đã chốt: hệ thống có đúng một Administrator, khởi tạo ở cấp 3, không ai nâng / hạ được
--     (đổi cấp ADMIN qua API -> 403 ERR_AC_013, BR-AL-32).
--
-- Ràng buộc:
--   - Chỉ đổi dòng role = 'ADMIN' AND access_level <> 3. DB đã đúng (vd. DB dev) thì không đổi gì, không ghi audit.
--   - Audit ghi TRƯỚC câu UPDATE để còn đọc cấp cũ. Cùng dạng với audit ứng dụng ghi khi FM đổi cấp
--     (UserService.updateAccessLevel): target USER_ACCESS_LEVEL / UPDATE, target_id = subject_user_id = id người dùng,
--     old_value / new_value = {"accessLevel": n} (UserAccessLevelAuditSnapshot). Actor SYSTEM, changed_by NULL,
--     actor_source FLYWAY_MIGRATION_V132 (chk_audit_actor, V55); mọi dòng chung một correlation_id (md5 cố định, mẫu V83 / V86).
--     Cặp (USER_ACCESS_LEVEL, UPDATE) đã có trong audit_event_types từ V55 (FK fk_audit_logs_event_type).
--   - KHÔNG thêm CHECK (role <> 'ADMIN' OR access_level = 3) ở migration này: 20 file test tích hợp đang lưu ADMIN
--     cấp 1 / 2; Lucas quyết sau. Không thêm unique index "chỉ 1 ADMIN".
--   - Không đổi cấu hình ACCESS_LEVEL_DEFAULT_ADMIN. Chạy lại không lỗi: lần sau không còn dòng thoả điều kiện.
--   - Kiểm bằng V132AdminAccessLevelMigrationTest (chạy nguyên văn file này trên dữ liệu dựng, rồi rollback).
-- ============================================================================


-- ----------------------------------------------------------------------------
-- Bước 1. Audit USER_ACCESS_LEVEL / UPDATE cho từng ADMIN sắp được đưa về cấp 3
-- ----------------------------------------------------------------------------
INSERT INTO audit_logs (id, target_type, action, target_id, area_id, subject_user_id,
                        old_value, new_value, reason, changed_by, changed_at,
                        actor_type, actor_source, correlation_id)
SELECT gen_random_uuid(), 'USER_ACCESS_LEVEL', 'UPDATE', u.id::text, NULL, u.id,
       jsonb_build_object('accessLevel', u.access_level),
       jsonb_build_object('accessLevel', 3),
       'Quản trị viên luôn ở cấp truy cập 3 (V132)',
       NULL, CURRENT_TIMESTAMP, 'SYSTEM', 'FLYWAY_MIGRATION_V132', md5('FLYWAY_MIGRATION_V132')::uuid
FROM users u
WHERE u.role = 'ADMIN'
  AND u.access_level <> 3;


-- ----------------------------------------------------------------------------
-- Bước 2. Đưa ADMIN về cấp 3
-- ----------------------------------------------------------------------------
UPDATE users
SET access_level = 3,
    updated_at   = CURRENT_TIMESTAMP
WHERE role = 'ADMIN'
  AND access_level <> 3;


-- ----------------------------------------------------------------------------
-- Bước 3. Kiểm sau khi chạy (chỉ để người chạy tay đối chiếu, Flyway không cần)
-- ----------------------------------------------------------------------------
-- SELECT user_code, access_level FROM users WHERE role = 'ADMIN';                         -- mọi dòng = 3
-- SELECT target_id, old_value, new_value FROM audit_logs WHERE actor_source = 'FLYWAY_MIGRATION_V132';
