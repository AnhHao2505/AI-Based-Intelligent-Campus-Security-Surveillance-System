-- V18__refactor_area_level_enum_and_cleanup.sql
-- 1. Chuyển đổi area_level từ SMALLINT sang VARCHAR(30) Enum
-- Drop foreign key constraint cũ tới bảng area_levels
ALTER TABLE areas DROP CONSTRAINT IF EXISTS areas_area_level_fkey;

-- Drop index cũ trên area_level nếu có
DROP INDEX IF EXISTS ix_areas_level;

-- Đổi kiểu dữ liệu cột area_level từ smallint hoặc varchar cũ sang varchar(30) với mapping: 1/'1'/'PUBLIC' -> PUBLIC, 2/'2'/'SEMI_PRIVATE' -> SEMI_PRIVATE, 3/'3'/'PRIVATE' -> PRIVATE
ALTER TABLE areas ALTER COLUMN area_level TYPE VARCHAR(30) USING (
    CASE area_level::text
        WHEN '1' THEN 'PUBLIC'
        WHEN '2' THEN 'SEMI_PRIVATE'
        WHEN '3' THEN 'PRIVATE'
        WHEN 'PUBLIC' THEN 'PUBLIC'
        WHEN 'SEMI_PRIVATE' THEN 'SEMI_PRIVATE'
        WHEN 'PRIVATE' THEN 'PRIVATE'
        ELSE 'PUBLIC'
    END
);

-- Thêm check constraint cho area_level enum (drop trước nếu đã tồn tại)
ALTER TABLE areas DROP CONSTRAINT IF EXISTS chk_areas_area_level;
ALTER TABLE areas ADD CONSTRAINT chk_areas_area_level CHECK (area_level IN ('PUBLIC', 'SEMI_PRIVATE', 'PRIVATE'));

-- Tạo lại index trên cột area_level mới
DROP INDEX IF EXISTS ix_areas_level;
CREATE INDEX IF NOT EXISTS ix_areas_level ON areas(area_level);

-- Drop bảng area_levels không còn dùng
DROP TABLE IF EXISTS area_levels CASCADE;

-- 2. Dọn dẹp các cột đã bỏ ở bảng areas
ALTER TABLE areas DROP CONSTRAINT IF EXISTS ck_areas_map;
ALTER TABLE areas DROP COLUMN IF EXISTS map_x;
ALTER TABLE areas DROP COLUMN IF EXISTS map_y;
ALTER TABLE areas DROP COLUMN IF EXISTS created_by;
ALTER TABLE areas DROP COLUMN IF EXISTS updated_by;

-- 3. Dọn dẹp các cột comment không dùng ở bảng cameras
ALTER TABLE cameras DROP COLUMN IF EXISTS floor;
ALTER TABLE cameras DROP COLUMN IF EXISTS zone_name;
ALTER TABLE cameras DROP COLUMN IF EXISTS x;
ALTER TABLE cameras DROP COLUMN IF EXISTS y;

-- 4. Dọn dẹp các cột comment không dùng ở bảng camera_ai_configurations
ALTER TABLE camera_ai_configurations DROP COLUMN IF EXISTS loitering_detection_enabled;
ALTER TABLE camera_ai_configurations DROP COLUMN IF EXISTS loitering_threshold_seconds;
ALTER TABLE camera_ai_configurations DROP COLUMN IF EXISTS model_version;

-- 5. Dọn dẹp các cột comment và chuẩn hóa ở bảng camera_stream_configurations
ALTER TABLE camera_stream_configurations DROP COLUMN IF EXISTS stream_enabled;
ALTER TABLE camera_stream_configurations DROP COLUMN IF EXISTS reconnect_enabled;
ALTER TABLE camera_stream_configurations DROP COLUMN IF EXISTS main_stream_url;
ALTER TABLE camera_stream_configurations DROP COLUMN IF EXISTS sub_stream_url;

-- 6. Dọn dẹp các bảng log và temporary usages không còn dùng
DROP TABLE IF EXISTS area_change_logs CASCADE;
DROP TABLE IF EXISTS area_temporary_usage_change_logs CASCADE;
DROP TABLE IF EXISTS area_temporary_usages CASCADE;

