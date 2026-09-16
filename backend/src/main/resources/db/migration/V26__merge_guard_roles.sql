-- Merge guard roles, seed the demo guard, and align the legacy stream schema.
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;

UPDATE users
SET role = 'GUARD'
WHERE role IN ('INTERNAL_GUARD', 'OUTSOURCED_GUARD');

ALTER TABLE users ADD CONSTRAINT chk_users_role
    CHECK (role IN ('ADMIN', 'FACILITY_MANAGER', 'GUARD', 'NORMAL_USER'));

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

ALTER TABLE camera_stream_configurations
    ADD COLUMN IF NOT EXISTS main_stream_path VARCHAR(512),
    ADD COLUMN IF NOT EXISTS sub_stream_path VARCHAR(512),
    ADD COLUMN IF NOT EXISTS retries_before_alert INT;

UPDATE camera_stream_configurations
SET main_stream_path = COALESCE(main_stream_path, ''),
    retries_before_alert = COALESCE(retries_before_alert, 3);

ALTER TABLE camera_stream_configurations
    ALTER COLUMN main_stream_path SET NOT NULL,
    ALTER COLUMN retries_before_alert SET NOT NULL;