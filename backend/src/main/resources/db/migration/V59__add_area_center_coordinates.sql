-- ============================================================================
-- V59: Bổ sung tọa độ trung tâm (center_latitude, center_longitude) cho bảng areas
-- Phục vụ hiển thị vị trí khu vực trên bản đồ mobile & định vị tuần tra bảo vệ
-- ============================================================================

-- 1. Thêm cột center_latitude và center_longitude
ALTER TABLE areas
ADD COLUMN IF NOT EXISTS center_latitude DOUBLE PRECISION,
ADD COLUMN IF NOT EXISTS center_longitude DOUBLE PRECISION;

COMMENT ON COLUMN areas.center_latitude IS 'Tọa độ vĩ độ trung tâm khu vực (WGS84, vd: 10.84175)';
COMMENT ON COLUMN areas.center_longitude IS 'Tọa độ kinh độ trung tâm khu vực (WGS84, vd: 106.80922)';

-- 2. Backfill tọa độ cho các khu vực demo FPT_AROUND tầng G (nếu chưa có tọa độ)
UPDATE areas
SET center_latitude = 10.84175, center_longitude = 106.80922
WHERE name ILIKE '%cổng%' AND center_latitude IS NULL;

UPDATE areas
SET center_latitude = 10.84105, center_longitude = 106.80973
WHERE (name ILIKE '%hồ sen%' OR name ILIKE '%lotus%') AND center_latitude IS NULL;

UPDATE areas
SET center_latitude = 10.84148, center_longitude = 106.81008
WHERE (name ILIKE '%thư viện%' OR name ILIKE '%lib%') AND center_latitude IS NULL;

UPDATE areas
SET center_latitude = 10.84165, center_longitude = 106.80952
WHERE (name ILIKE '%y tế%' OR name ILIKE '%med%') AND center_latitude IS NULL;

UPDATE areas
SET center_latitude = 10.84180, center_longitude = 106.81030
WHERE (name ILIKE '%lb01%' OR name ILIKE '%phòng lb01%') AND center_latitude IS NULL;

UPDATE areas
SET center_latitude = 10.84185, center_longitude = 106.81045
WHERE (name ILIKE '%lb02%' OR name ILIKE '%phòng lb02%') AND center_latitude IS NULL;
