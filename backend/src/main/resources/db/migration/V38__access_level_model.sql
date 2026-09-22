-- ============================================================================
-- V38: Access Level Model & Explicit Authorization Flag
-- ============================================================================

-- 1. users: access_level (1..3), mặc định 1. Backfill GUARD & FACILITY_MANAGER = 2
ALTER TABLE users ADD COLUMN access_level SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE users ADD CONSTRAINT chk_users_access_level CHECK (access_level BETWEEN 1 AND 3);

UPDATE users
SET access_level = 2
WHERE role IN ('GUARD', 'FACILITY_MANAGER');

-- 2. areas: area_access_level (1..3), mặc định 3; explicit_authorization_required, mặc định true
ALTER TABLE areas ADD COLUMN area_access_level SMALLINT NOT NULL DEFAULT 3;
ALTER TABLE areas ADD CONSTRAINT chk_areas_area_access_level CHECK (area_access_level BETWEEN 1 AND 3);
ALTER TABLE areas ADD COLUMN explicit_authorization_required BOOLEAN NOT NULL DEFAULT true;

-- Backfill areas theo area_level
UPDATE areas
SET area_access_level = 1,
    explicit_authorization_required = false
WHERE area_level = 'PUBLIC';

UPDATE areas
SET area_access_level = 2,
    explicit_authorization_required = false
WHERE area_level = 'SEMI_PRIVATE';

UPDATE areas
SET area_access_level = 3,
    explicit_authorization_required = true
WHERE area_level = 'PRIVATE';

-- 3. Bảng mới area_level_presets
CREATE TABLE area_level_presets (
    area_level                      VARCHAR(30) PRIMARY KEY,
    area_access_level               SMALLINT NOT NULL,
    explicit_authorization_required BOOLEAN NOT NULL,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                      UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_preset_area_level CHECK (area_level IN ('PUBLIC', 'SEMI_PRIVATE', 'PRIVATE')),
    CONSTRAINT chk_preset_area_access_level CHECK (area_access_level BETWEEN 1 AND 3)
);

INSERT INTO area_level_presets (area_level, area_access_level, explicit_authorization_required)
VALUES
    ('PUBLIC', 1, false),
    ('SEMI_PRIVATE', 2, false),
    ('PRIVATE', 3, true);

-- 4. Level mặc định theo role trong system_configurations
INSERT INTO system_configurations (
    config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable
) VALUES
(
    'ACCESS_LEVEL_DEFAULT_NORMAL_USER',
    '1',
    'INTEGER',
    1,
    3,
    NULL,
    'ACCESS_LEVEL',
    1,
    'Cấp độ truy cập mặc định cho người dùng thông thường',
    true
),
(
    'ACCESS_LEVEL_DEFAULT_GUARD',
    '2',
    'INTEGER',
    1,
    3,
    NULL,
    'ACCESS_LEVEL',
    2,
    'Cấp độ truy cập mặc định cho nhân viên bảo vệ',
    true
),
(
    'ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER',
    '2',
    'INTEGER',
    1,
    3,
    NULL,
    'ACCESS_LEVEL',
    3,
    'Cấp độ truy cập mặc định cho Quản lý cơ sở vật chất',
    true
),
(
    'ACCESS_LEVEL_DEFAULT_ADMIN',
    '1',
    'INTEGER',
    1,
    3,
    NULL,
    'ACCESS_LEVEL',
    4,
    'Cấp độ truy cập mặc định cho Quản trị viên',
    true
)
ON CONFLICT (config_key) DO NOTHING;
