-- ============================================================================
-- V58: Tái cấu trúc quan hệ Area - Camera từ N:N sang 1:N
-- Một camera chỉ thuộc tối đa 1 khu vực (area_id trên bảng cameras)
-- ============================================================================

-- 1. Thêm cột area_id vào bảng cameras và đánh chỉ mục
ALTER TABLE cameras 
ADD COLUMN IF NOT EXISTS area_id UUID REFERENCES areas(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_cameras_area_id ON cameras(area_id);

-- 2. Chuyển đổi dữ liệu đang có từ bảng trung gian area_cameras sang cameras.area_id
-- Xử lý trường hợp 1 camera đang được gán nhiều khu vực bằng DISTINCT ON (camera_id)
DO $$
BEGIN
    IF EXISTS (
        SELECT FROM information_schema.tables 
        WHERE table_schema = 'public' AND table_name = 'area_cameras'
    ) THEN
        UPDATE cameras c
        SET area_id = sub.area_id
        FROM (
            SELECT DISTINCT ON (camera_id) camera_id, area_id
            FROM area_cameras
            ORDER BY camera_id, area_id
        ) sub
        WHERE c.id = sub.camera_id;
    END IF;
END $$;

-- 3. Xóa bảng trung gian area_cameras
DROP TABLE IF EXISTS area_cameras;
