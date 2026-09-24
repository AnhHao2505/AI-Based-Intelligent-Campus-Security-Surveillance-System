-- ============================================================================
-- V45: Hierarchical Facility Architecture: Buildings -> Floors -> Areas
-- Chuẩn hóa mô hình kiến trúc phân cấp: Tòa nhà -> Tầng & Sơ đồ -> Khu vực
-- ============================================================================

-- 1. Bảng buildings (Tòa nhà / Phân khu chức năng)
CREATE TABLE IF NOT EXISTS buildings (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(50) NOT NULL,
    name         VARCHAR(150) NOT NULL,
    description  TEXT,
    total_floors INTEGER NOT NULL DEFAULT 1,
    is_active    BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_buildings_code ON buildings (LOWER(code));
CREATE INDEX IF NOT EXISTS idx_buildings_active ON buildings (is_active);

-- 2. Bảng floors (Tầng & Sơ đồ mặt bằng tương ứng)
CREATE TABLE IF NOT EXISTS floors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    building_id     UUID NOT NULL REFERENCES buildings(id) ON DELETE CASCADE,
    floor_code      VARCHAR(20) NOT NULL,
    name            VARCHAR(100) NOT NULL,
    floor_order     INTEGER NOT NULL DEFAULT 0,
    image_key       VARCHAR(255),
    original_width  INTEGER,
    original_height INTEGER,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_floors_building_code ON floors (building_id, LOWER(floor_code));
CREATE INDEX IF NOT EXISTS idx_floors_building ON floors (building_id, floor_order);

-- 3. Nâng cấp bảng areas: liên kết trực tiếp tới floor_id
ALTER TABLE areas ADD COLUMN IF NOT EXISTS floor_id UUID REFERENCES floors(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_areas_floor_id ON areas (floor_id);

-- 4. Seed dữ liệu danh mục Tòa nhà chuẩn
INSERT INTO buildings (code, name, description, total_floors, is_active)
VALUES
    ('FPT_AROUND', 'Khuôn viên Ngoài trời & Sảnh', 'Khuôn viên ngoại cảnh, sân trường, hồ sen và sảnh đón', 3, true),
    ('TOA_ALPHA', 'Tòa Alpha - Giảng đường chính', 'Khối phòng học lý thuyết, hội trường và phòng ban chức năng', 5, true),
    ('TOA_BETA', 'Tòa Beta - Phòng Lab & Kỹ thuật', 'Khối thực hành máy tính, phòng lab công nghệ và studio', 4, true),
    ('KHU_THE_THAO', 'Khu Thể Thao & Sân Bóng', 'Sân bóng đá, bóng rổ, nhà thi đấu thể thao ngoài trời', 1, true)
ON CONFLICT (LOWER(code)) DO UPDATE 
SET name = EXCLUDED.name, description = EXCLUDED.description;

-- 5. Seed các tầng cho từng Tòa nhà
-- 5.1. Tầng cho FPT_AROUND (kèm sơ đồ mặt bằng từ floor_plans cũ nếu có)
INSERT INTO floors (building_id, floor_code, name, floor_order, image_key, original_width, original_height, is_active)
SELECT 
    b.id,
    'G',
    'Tầng Trệt',
    0,
    'fptaround-floor-g-v1.png',
    1838,
    963,
    true
FROM buildings b WHERE b.code = 'FPT_AROUND'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, image_key, original_width, original_height, is_active)
SELECT 
    b.id,
    '1',
    'Tầng 1',
    1,
    'fptaround-floor-01-v1.png',
    1532,
    803,
    true
FROM buildings b WHERE b.code = 'FPT_AROUND'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, image_key, original_width, original_height, is_active)
SELECT 
    b.id,
    '2',
    'Tầng 2',
    2,
    'fptaround-floor-02-v1.png',
    1532,
    803,
    true
FROM buildings b WHERE b.code = 'FPT_AROUND'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

-- 5.2. Tầng cho TOA_ALPHA
INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, 'G', 'Tầng Trệt', 0, true FROM buildings b WHERE b.code = 'TOA_ALPHA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, '1', 'Tầng 1', 1, true FROM buildings b WHERE b.code = 'TOA_ALPHA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, '2', 'Tầng 2', 2, true FROM buildings b WHERE b.code = 'TOA_ALPHA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, '3', 'Tầng 3', 3, true FROM buildings b WHERE b.code = 'TOA_ALPHA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, '4', 'Tầng 4', 4, true FROM buildings b WHERE b.code = 'TOA_ALPHA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

-- 5.3. Tầng cho TOA_BETA
INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, 'G', 'Tầng Trệt', 0, true FROM buildings b WHERE b.code = 'TOA_BETA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, '1', 'Tầng 1', 1, true FROM buildings b WHERE b.code = 'TOA_BETA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, '2', 'Tầng 2', 2, true FROM buildings b WHERE b.code = 'TOA_BETA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, '3', 'Tầng 3', 3, true FROM buildings b WHERE b.code = 'TOA_BETA'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

-- 5.4. Tầng cho KHU_THE_THAO
INSERT INTO floors (building_id, floor_code, name, floor_order, is_active)
SELECT b.id, 'G', 'Mặt sân', 0, true FROM buildings b WHERE b.code = 'KHU_THE_THAO'
ON CONFLICT (building_id, LOWER(floor_code)) DO NOTHING;

-- 6. Chuẩn hóa dữ liệu cũ trong bảng areas và liên kết floor_id
-- 6.1. Chuẩn hóa các mã building cũ (HCM, CO_SO_HCM, Tòa A, Tòa B)
UPDATE areas 
SET building = 'FPT_AROUND' 
WHERE building IS NULL OR building IN ('HCM', 'CO_SO_HCM', 'FPT TP.HCM');

UPDATE areas SET building = 'TOA_ALPHA' WHERE building IN ('Tòa A', 'TOA_A', 'ALPHA');
UPDATE areas SET building = 'TOA_BETA' WHERE building IN ('Tòa B', 'TOA_B', 'BETA');

-- 6.2. Cập nhật floor mặc định là 'G' nếu để trống
UPDATE areas 
SET floor = 'G' 
WHERE floor IS NULL OR TRIM(floor) = '';

-- 6.3. Gán floor_id tương ứng cho các areas hiện tại
UPDATE areas a
SET floor_id = f.id
FROM floors f
JOIN buildings b ON f.building_id = b.id
WHERE LOWER(a.building) = LOWER(b.code)
  AND LOWER(a.floor) = LOWER(f.floor_code)
  AND a.floor_id IS NULL;
