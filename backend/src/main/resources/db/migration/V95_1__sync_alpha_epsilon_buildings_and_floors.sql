-- ============================================================================
-- V95_1: Đồng bộ danh mục tòa nhà chuẩn về 'Tòa Alpha' (3 tầng) & 'Tòa Epsilon' (5 tầng)
-- Sub-version _1 sau V95 để tránh trùng lặp version; tương thích flyway.out-of-order=true
-- ============================================================================

DO $$
DECLARE
    v_alpha_id UUID;
    v_beta_id UUID;
    v_epsilon_id UUID;
BEGIN
    -- 1. Chuẩn hóa 'Tòa Alpha' (3 tầng: Tầng Trệt=0, Tầng 1=1, Tầng 2=2)
    SELECT id INTO v_alpha_id FROM buildings WHERE LOWER(TRIM(name)) = 'tòa alpha' LIMIT 1;

    IF v_alpha_id IS NULL THEN
        INSERT INTO buildings (name, description, total_floors, is_active)
        VALUES (
            'Tòa Alpha',
            'Khối giảng đường lý thuyết, hội trường và phòng ban chức năng',
            3,
            true
        )
        RETURNING id INTO v_alpha_id;
    ELSE
        UPDATE buildings
        SET name = 'Tòa Alpha',
            total_floors = 3,
            is_active = true,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = v_alpha_id;
    END IF;

    INSERT INTO floors (building_id, name, floor_order, is_active)
    VALUES
        (v_alpha_id, 'Tầng Trệt', 0, true),
        (v_alpha_id, 'Tầng 1', 1, true),
        (v_alpha_id, 'Tầng 2', 2, true)
    ON CONFLICT ON CONSTRAINT uq_floors_building_name
    DO UPDATE SET
        floor_order = EXCLUDED.floor_order,
        is_active = true,
        updated_at = CURRENT_TIMESTAMP;

    -- 2. Chuyển 'Tòa Beta' -> 'Tòa Epsilon' (5 tầng: Tầng Trệt=0 .. Tầng 4=4)
    SELECT id INTO v_epsilon_id FROM buildings WHERE LOWER(TRIM(name)) = 'tòa epsilon' LIMIT 1;
    SELECT id INTO v_beta_id FROM buildings WHERE LOWER(TRIM(name)) = 'tòa beta' LIMIT 1;

    IF v_beta_id IS NOT NULL AND v_epsilon_id IS NULL THEN
        UPDATE buildings
        SET name = 'Tòa Epsilon',
            description = 'Khối thực hành máy tính, phòng lab công nghệ, studio và nghiên cứu chuyên sâu',
            total_floors = 5,
            is_active = true,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = v_beta_id;
        v_epsilon_id := v_beta_id;
    ELSIF v_epsilon_id IS NOT NULL THEN
        UPDATE buildings
        SET name = 'Tòa Epsilon',
            total_floors = 5,
            is_active = true,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = v_epsilon_id;
    ELSE
        INSERT INTO buildings (name, description, total_floors, is_active)
        VALUES (
            'Tòa Epsilon',
            'Khối thực hành máy tính, phòng lab công nghệ, studio và nghiên cứu chuyên sâu',
            5,
            true
        )
        RETURNING id INTO v_epsilon_id;
    END IF;

    INSERT INTO floors (building_id, name, floor_order, is_active)
    VALUES
        (v_epsilon_id, 'Tầng Trệt', 0, true),
        (v_epsilon_id, 'Tầng 1', 1, true),
        (v_epsilon_id, 'Tầng 2', 2, true),
        (v_epsilon_id, 'Tầng 3', 3, true),
        (v_epsilon_id, 'Tầng 4', 4, true)
    ON CONFLICT ON CONSTRAINT uq_floors_building_name
    DO UPDATE SET
        floor_order = EXCLUDED.floor_order,
        is_active = true,
        updated_at = CURRENT_TIMESTAMP;

    -- 3. Đồng bộ cột building dạng chuỗi trên các bảng nghiệp vụ (idempotent)
    UPDATE areas
    SET building = 'Tòa Epsilon',
        updated_at = CURRENT_TIMESTAMP
    WHERE building IS NOT NULL
      AND LOWER(TRIM(building)) IN ('tòa beta', 'tòa espilon');

    UPDATE areas
    SET building = 'Tòa Alpha',
        updated_at = CURRENT_TIMESTAMP
    WHERE building IS NOT NULL
      AND LOWER(TRIM(building)) IN ('tòa hcm', 'tòa fpt hcm');

    UPDATE security_incidents
    SET building = 'Tòa Epsilon'
    WHERE building IS NOT NULL
      AND LOWER(TRIM(building)) IN ('tòa beta', 'tòa espilon');

    UPDATE guard_teams
    SET description = REPLACE(description, 'Tòa Beta', 'Tòa Epsilon'),
        updated_at = CURRENT_TIMESTAMP
    WHERE description IS NOT NULL
      AND description LIKE '%Tòa Beta%';
END $$;
