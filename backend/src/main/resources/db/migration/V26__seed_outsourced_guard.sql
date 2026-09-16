ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role
    CHECK (role IN ('ADMIN', 'FACILITY_MANAGER', 'GUARD', 'NORMAL_USER', 'INTERNAL_GUARD', 'OUTSOURCED_GUARD'));

-- Seed the demo guard account used by the role quick-login button on the frontend.
INSERT INTO users (full_name, user_code, role, email, password, is_active, created_at, updated_at)
VALUES (
    'Bảo Vệ Demo',
    'SEC-002',
    'GUARD',
    'guard.demo@fpt.edu.vn',
    '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (LOWER(email)) WHERE deleted_at IS NULL DO UPDATE
SET
    full_name = EXCLUDED.full_name,
    user_code = EXCLUDED.user_code,
    role = EXCLUDED.role,
    password = EXCLUDED.password,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;