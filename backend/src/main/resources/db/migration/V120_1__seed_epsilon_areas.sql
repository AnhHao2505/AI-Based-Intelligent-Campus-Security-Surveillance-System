-- ============================================================================
-- V120_1: Seed danh mục 38 khu vực (areas) của Tòa Epsilon theo 5 tầng
-- Sub-version _1 sau V120 để tránh xung đột version với các thành viên khác trong team
-- Sử dụng ON CONFLICT (floor_id, lower(name)) WHERE deleted_at IS NULL để đảm bảo tính idempotent
-- ============================================================================

INSERT INTO areas (
    name,
    building,
    floor,
    floor_id,
    area_level,
    area_access_level,
    explicit_authorization_required,
    is_active,
    center_latitude,
    center_longitude
)
SELECT 
    d.area_name,
    'Tòa Epsilon',
    d.floor_name,
    f.id,
    d.area_level,
    d.area_access_level,
    false,
    true,
    d.center_latitude,
    d.center_longitude
FROM (
    VALUES
        -- Tầng Trệt (floor_order = 0)
        ('Tầng Trệt', 'KT Điện', 'HIGHLY_CONFIDENTIAL', 3, 10.840506, 106.811247),
        ('Tầng Trệt', 'P. ELV', 'CONFIDENTIAL', 2, 10.840541, 106.811212),
        ('Tầng Trệt', 'Phòng học 01-01', 'PUBLIC', 1, 10.840623, 106.811645),
        ('Tầng Trệt', 'Phòng học 01-02', 'PUBLIC', 1, 10.840572, 106.811704),
        ('Tầng Trệt', 'Phòng học 01-09', 'PUBLIC', 1, 10.840313, 106.811491),
        ('Tầng Trệt', 'Phòng học 01-19', 'PUBLIC', 1, 10.840623, 106.81126),
        ('Tầng Trệt', 'Phòng học 01-20', 'PUBLIC', 1, 10.840682, 106.811317),
        ('Tầng Trệt', 'Phòng học 01-21', 'PUBLIC', 1, 10.840622, 106.811379),
        ('Tầng Trệt', 'Phòng học 01-22', 'PUBLIC', 1, 10.840678, 106.811428),
        ('Tầng Trệt', 'Phòng học 01-23', 'PUBLIC', 1, 10.840807, 106.811432),
        ('Tầng Trệt', 'Phòng học 01-24', 'PUBLIC', 1, 10.840757, 106.811492),
        ('Tầng Trệt', 'Phòng Họp', 'CONFIDENTIAL', 2, 10.840476, 106.811264),
        ('Tầng Trệt', 'Phòng Tuyển Sinh', 'PUBLIC', 1, 10.840295, 106.81135),
        ('Tầng Trệt', 'P. MSB', 'HIGHLY_CONFIDENTIAL', 3, 10.840581, 106.811224),
        ('Tầng Trệt', 'P. nghỉ giáo viên', 'CONFIDENTIAL', 2, 10.840561, 106.811189),
        ('Tầng Trệt', 'Tạp vụ', 'PUBLIC', 1, 10.840347, 106.81143),
        ('Tầng Trệt', 'Tạp vụ 1', 'PUBLIC', 1, 10.840534, 106.811269),
        ('Tầng Trệt', 'Thư viện', 'PUBLIC', 1, 10.840431, 106.811189),
        ('Tầng Trệt', 'Thư viện 1', 'PUBLIC', 1, 10.840431, 106.811593),
        ('Tầng Trệt', 'Thư viện 2', 'PUBLIC', 1, 10.840492, 106.811526),
        ('Tầng Trệt', 'Văn Phòng', 'CONFIDENTIAL', 2, 10.840243, 106.811419),

        -- Tầng 1 (floor_order = 1)
        ('Tầng 1', 'KT Điện', 'HIGHLY_CONFIDENTIAL', 3, 10.840505, 106.811262),
        ('Tầng 1', 'Phòng họp', 'CONFIDENTIAL', 2, 10.840472, 106.811281),
        ('Tầng 1', 'Phòng thực hành 1', 'PUBLIC', 1, 10.840632, 106.811407),
        ('Tầng 1', 'Phòng thực hành 2', 'PUBLIC', 1, 10.840697, 106.811464),

        -- Tầng 2 (floor_order = 2)
        ('Tầng 2', 'KT Điện', 'HIGHLY_CONFIDENTIAL', 3, 10.840505, 106.811262),
        ('Tầng 2', 'Phòng họp', 'CONFIDENTIAL', 2, 10.840472, 106.811281),
        ('Tầng 2', 'Phòng thực hành 1', 'PUBLIC', 1, 10.840697, 106.811464),
        ('Tầng 2', 'Phòng thực hành 2', 'PUBLIC', 1, 10.840632, 106.811407),
        ('Tầng 2', 'Phòng thực hành 3', 'PUBLIC', 1, 10.840521, 106.811602),

        -- Tầng 3 (floor_order = 3)
        ('Tầng 3', 'KT Điện', 'HIGHLY_CONFIDENTIAL', 3, 10.840505, 106.811262),
        ('Tầng 3', 'Phòng họp', 'CONFIDENTIAL', 2, 10.840472, 106.811281),
        ('Tầng 3', 'Phòng thực hành', 'PUBLIC', 1, 10.840632, 106.811407),
        ('Tầng 3', 'Phòng thực hành 1', 'PUBLIC', 1, 10.840697, 106.811464),

        -- Tầng 4 (floor_order = 4)
        ('Tầng 4', 'KT Điện', 'HIGHLY_CONFIDENTIAL', 3, 10.840505, 106.811262),
        ('Tầng 4', 'Phòng diễn giả', 'CONFIDENTIAL', 2, 10.840472, 106.811281),
        ('Tầng 4', 'Phòng thực hành 1', 'PUBLIC', 1, 10.840632, 106.811407),
        ('Tầng 4', 'Phòng thực hành 2', 'PUBLIC', 1, 10.840697, 106.811464)
) AS d(floor_name, area_name, area_level, area_access_level, center_latitude, center_longitude)
JOIN buildings b ON LOWER(TRIM(b.name)) = 'tòa epsilon'
JOIN floors f ON f.building_id = b.id AND f.name = d.floor_name
ON CONFLICT (floor_id, lower(name)) WHERE deleted_at IS NULL
DO UPDATE SET
    building = EXCLUDED.building,
    floor = EXCLUDED.floor,
    area_level = EXCLUDED.area_level,
    area_access_level = EXCLUDED.area_access_level,
    explicit_authorization_required = EXCLUDED.explicit_authorization_required,
    is_active = EXCLUDED.is_active,
    center_latitude = EXCLUDED.center_latitude,
    center_longitude = EXCLUDED.center_longitude,
    updated_at = CURRENT_TIMESTAMP;
