-- ============================================================================
-- FLYWAY MIGRATION V6: SEED INITIAL USERS (FA26SE040)
-- Mật khẩu mặc định cho tất cả tài khoản là: 123456
-- BCrypt hash của '123456': $2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.
-- ============================================================================

INSERT INTO users (full_name, user_code, role, email, password, is_active, created_at, updated_at)
VALUES 
    ('Quản Trị Viên FPTU', 'AD-001', 'ADMIN', 'admin@fpt.edu.vn', '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Nguyễn Văn An (Bảo Vệ)', 'SEC-001', 'INTERNAL_GUARD', 'guard.an@fpt.edu.vn', '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Trần Bình (Quản Lý CSVC)', 'FM-001', 'FACILITY_MANAGER', 'manager.binh@fpt.edu.vn', '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (email) DO UPDATE 
SET 
    password = EXCLUDED.password,
    role = EXCLUDED.role,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;
