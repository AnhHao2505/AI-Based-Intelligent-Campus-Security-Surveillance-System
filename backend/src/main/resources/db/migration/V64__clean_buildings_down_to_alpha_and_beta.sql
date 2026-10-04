-- ============================================================================
-- V64: Clean data tòa nhà (buildings) và tầng (floors)
-- 1. Loại bỏ các cột code, floor_code, image_key, width, height không còn dùng
-- 2. Chuẩn hóa tên TOA_ALPHA thành 'Tòa Alpha', TOA_BETA thành 'Tòa Beta'
-- 3. Đảm bảo tồn tại Tầng Trệt và Tầng 1 cho Tòa Alpha và Tòa Beta
-- 4. Di dời và đồng bộ floor_id của tất cả khu vực về đúng Tòa Alpha / Tòa Beta
-- 5. Xóa các tầng khác ngoài Tầng Trệt và Tầng 1, xóa các tòa nhà ngoài Alpha và Beta
-- ============================================================================

-- 1. Chuẩn hóa tên hiển thị của TOA_ALPHA và TOA_BETA (nếu chưa)
UPDATE buildings
SET name = 'Tòa Alpha', description = 'Khối giảng đường và phòng chức năng chính'
WHERE UPPER(name) LIKE '%ALPHA%';

UPDATE buildings
SET name = 'Tòa Beta', description = 'Khối thực hành máy tính và phòng kỹ thuật'
WHERE UPPER(name) LIKE '%BETA%';

-- 2. Đảm bảo Tòa Alpha và Tòa Beta đều tồn tại
DO $$
DECLARE
    v_alpha_id UUID;
    v_beta_id UUID;
    v_alpha_g_id UUID;
    v_alpha_1_id UUID;
    v_beta_g_id UUID;
    v_beta_1_id UUID;
BEGIN
    SELECT id INTO v_alpha_id FROM buildings WHERE UPPER(name) LIKE '%ALPHA%' LIMIT 1;
    IF v_alpha_id IS NULL THEN
        INSERT INTO buildings (name, description, total_floors, is_active, created_at, updated_at)
        VALUES ('Tòa Alpha', 'Khối giảng đường và phòng chức năng chính', 2, true, NOW(), NOW())
        RETURNING id INTO v_alpha_id;
    END IF;

    SELECT id INTO v_beta_id FROM buildings WHERE UPPER(name) LIKE '%BETA%' LIMIT 1;
    IF v_beta_id IS NULL THEN
        INSERT INTO buildings (name, description, total_floors, is_active, created_at, updated_at)
        VALUES ('Tòa Beta', 'Khối thực hành máy tính và phòng kỹ thuật', 2, true, NOW(), NOW())
        RETURNING id INTO v_beta_id;
    END IF;

    -- TOA_ALPHA Tầng Trệt
    SELECT id INTO v_alpha_g_id FROM floors WHERE building_id = v_alpha_id AND name = 'Tầng Trệt' LIMIT 1;
    IF v_alpha_g_id IS NULL THEN
        INSERT INTO floors (building_id, name, floor_order, is_active)
        VALUES (v_alpha_id, 'Tầng Trệt', 0, true)
        RETURNING id INTO v_alpha_g_id;
    ELSE
        UPDATE floors SET floor_order = 0, is_active = true WHERE id = v_alpha_g_id;
    END IF;

    -- TOA_ALPHA Tầng 1
    SELECT id INTO v_alpha_1_id FROM floors WHERE building_id = v_alpha_id AND name = 'Tầng 1' LIMIT 1;
    IF v_alpha_1_id IS NULL THEN
        INSERT INTO floors (building_id, name, floor_order, is_active)
        VALUES (v_alpha_id, 'Tầng 1', 1, true)
        RETURNING id INTO v_alpha_1_id;
    ELSE
        UPDATE floors SET floor_order = 1, is_active = true WHERE id = v_alpha_1_id;
    END IF;

    -- TOA_BETA Tầng Trệt
    SELECT id INTO v_beta_g_id FROM floors WHERE building_id = v_beta_id AND name = 'Tầng Trệt' LIMIT 1;
    IF v_beta_g_id IS NULL THEN
        INSERT INTO floors (building_id, name, floor_order, is_active)
        VALUES (v_beta_id, 'Tầng Trệt', 0, true)
        RETURNING id INTO v_beta_g_id;
    ELSE
        UPDATE floors SET floor_order = 0, is_active = true WHERE id = v_beta_g_id;
    END IF;

    -- TOA_BETA Tầng 1
    SELECT id INTO v_beta_1_id FROM floors WHERE building_id = v_beta_id AND name = 'Tầng 1' LIMIT 1;
    IF v_beta_1_id IS NULL THEN
        INSERT INTO floors (building_id, name, floor_order, is_active)
        VALUES (v_beta_id, 'Tầng 1', 1, true)
        RETURNING id INTO v_beta_1_id;
    ELSE
        UPDATE floors SET floor_order = 1, is_active = true WHERE id = v_beta_1_id;
    END IF;

    -- Di chuyển các khu vực thuộc tầng 1 về Tầng 1 Tòa Alpha
    UPDATE areas
    SET building = 'Tòa Alpha',
        floor = 'Tầng 1',
        floor_id = v_alpha_1_id
    WHERE UPPER(COALESCE(floor, '')) IN ('1', 'TẦNG 1');

    -- Tất cả khu vực còn lại gán về Tầng Trệt Tòa Alpha
    UPDATE areas
    SET building = 'Tòa Alpha',
        floor = 'Tầng Trệt',
        floor_id = v_alpha_g_id
    WHERE floor_id IS NULL OR floor_id NOT IN (v_alpha_g_id, v_alpha_1_id, v_beta_g_id, v_beta_1_id);

    -- Đồng bộ chuỗi building và floor trên bảng areas tương ứng với floor_id
    UPDATE areas SET building = 'Tòa Alpha', floor = 'Tầng Trệt' WHERE floor_id = v_alpha_g_id;
    UPDATE areas SET building = 'Tòa Alpha', floor = 'Tầng 1' WHERE floor_id = v_alpha_1_id;
    UPDATE areas SET building = 'Tòa Beta', floor = 'Tầng Trệt' WHERE floor_id = v_beta_g_id;
    UPDATE areas SET building = 'Tòa Beta', floor = 'Tầng 1' WHERE floor_id = v_beta_1_id;
END $$;

-- 3. Xóa các tầng không phải Tầng Trệt hoặc Tầng 1
DELETE FROM floors
WHERE name NOT IN ('Tầng Trệt', 'Tầng 1');

-- 4. Xóa các tầng thuộc tòa nhà khác
DELETE FROM floors
WHERE building_id NOT IN (
    SELECT id FROM buildings
    WHERE UPPER(name) IN ('TÒA ALPHA', 'TÒA BETA')
);

-- 5. Xóa các tòa nhà khác ngoài Tòa Alpha và Tòa Beta
DELETE FROM buildings
WHERE UPPER(name) NOT IN ('TÒA ALPHA', 'TÒA BETA');

-- 6. Loại bỏ cột code khỏi buildings, floor_code và các thông tin sơ đồ ảnh khỏi floors
ALTER TABLE buildings DROP COLUMN IF EXISTS code CASCADE;
ALTER TABLE floors DROP COLUMN IF EXISTS floor_code CASCADE;
ALTER TABLE floors DROP COLUMN IF EXISTS image_key CASCADE;
ALTER TABLE floors DROP COLUMN IF EXISTS original_width CASCADE;
ALTER TABLE floors DROP COLUMN IF EXISTS original_height CASCADE;
ALTER TABLE floors DROP COLUMN IF EXISTS width CASCADE;
ALTER TABLE floors DROP COLUMN IF EXISTS height CASCADE;

-- 7. Đảm bảo ràng buộc duy nhất (Unique constraint) trên name
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_buildings_name') THEN
        ALTER TABLE buildings ADD CONSTRAINT uq_buildings_name UNIQUE (name);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_floors_building_name') THEN
        ALTER TABLE floors ADD CONSTRAINT uq_floors_building_name UNIQUE (building_id, name);
    END IF;
END $$;

-- 8. Mở rộng độ dài cột floor trên bảng areas để chứa tên tầng đầy đủ
ALTER TABLE areas ALTER COLUMN floor TYPE VARCHAR(50);


