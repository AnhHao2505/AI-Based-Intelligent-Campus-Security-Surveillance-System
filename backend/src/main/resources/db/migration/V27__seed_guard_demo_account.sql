-- Seed the single demo guard account used by the frontend role bypass.
UPDATE users
SET
    full_name = 'Bảo Vệ Demo',
    user_code = 'SEC-002',
    role = 'GUARD',
    password = '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.',
    is_active = true,
    updated_at = CURRENT_TIMESTAMP
WHERE email = 'guard.demo@fpt.edu.vn';

INSERT INTO users (full_name, user_code, role, email, password, is_active, created_at, updated_at)
SELECT
    'Bảo Vệ Demo',
    'SEC-002',
    'GUARD',
    'guard.demo@fpt.edu.vn',
    '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE email = 'guard.demo@fpt.edu.vn'
);