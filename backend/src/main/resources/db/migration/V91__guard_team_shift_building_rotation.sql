-- ============================================================================
-- V91: Guard Team and Shift Building Association & Cross-Building Rotation (P1b)
-- ============================================================================

-- 1. Bổ sung building_id vào bảng guard_teams
ALTER TABLE guard_teams ADD COLUMN IF NOT EXISTS building_id UUID REFERENCES buildings(id) ON DELETE RESTRICT;
CREATE INDEX IF NOT EXISTS idx_guard_teams_building_id ON guard_teams (building_id);

-- 2. Điền sẵn building_id cho đội cũ (khớp description và buildings.name không phân biệt hoa thường)
-- Giữ nguyên description, không sửa nội dung
UPDATE guard_teams gt
SET building_id = b.id
FROM buildings b
WHERE gt.description IS NOT NULL
  AND lower(trim(gt.description)) = lower(trim(b.name));

-- 3. Bổ sung các cột toà nhà và cờ luân chuyển vào bảng guard_shifts
ALTER TABLE guard_shifts ADD COLUMN IF NOT EXISTS building_id UUID REFERENCES buildings(id) ON DELETE RESTRICT;
ALTER TABLE guard_shifts ADD COLUMN IF NOT EXISTS is_cross_building BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE guard_shifts ADD COLUMN IF NOT EXISTS cross_building_reason VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_guard_shifts_building_date ON guard_shifts (building_id, shift_date);

-- 4. Điền sẵn toà nhà và cờ luân chuyển cho ca cũ
-- 4.1. Điền toà theo khu vực (qua floor -> building) nếu ca có khu vực
UPDATE guard_shifts gs
SET building_id = f.building_id
FROM areas a
JOIN floors f ON a.floor_id = f.id
WHERE gs.area_id IS NOT NULL
  AND gs.area_id = a.id;

-- 4.2. Điền toà theo đội của bảo vệ cho các ca chưa có toà nhà
UPDATE guard_shifts gs
SET building_id = gt.building_id
FROM users u
JOIN guard_teams gt ON u.team_id = gt.id
WHERE gs.building_id IS NULL
  AND gs.guard_id = u.id
  AND gt.building_id IS NOT NULL;

-- 4.3. Đánh dấu is_cross_building = true khi cả toà của ca và toà của đội đều có và khác nhau
UPDATE guard_shifts gs
SET is_cross_building = TRUE
FROM users u
JOIN guard_teams gt ON u.team_id = gt.id
WHERE gs.guard_id = u.id
  AND gs.building_id IS NOT NULL
  AND gt.building_id IS NOT NULL
  AND gs.building_id <> gt.building_id;

