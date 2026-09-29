-- ============================================================================
-- FLYWAY MIGRATION V53: BACKFILL FLOOR_ID AND ENFORCE UNIQUE ACTIVE AREA NAME PER FLOOR
-- 1. Backfill floor_id cho khu vực đang NULL bằng cách khớp building (code) và floor (floor_code)
-- 2. Kiểm tra an toàn: nếu còn khu vực active nào floor_id NULL thì ném exception
-- 3. Đặt ràng buộc NOT NULL cho cột floor_id
-- 4. Tạo partial unique index chống trùng tên khu vực đang hoạt động trên cùng một tầng
-- ============================================================================

-- 1. Backfill floor_id cho các khu vực có floor_id IS NULL
UPDATE areas a
SET floor_id = f.id
FROM floors f
JOIN buildings b ON f.building_id = b.id
WHERE a.floor_id IS NULL
  AND lower(trim(a.building)) = lower(trim(b.code))
  AND lower(trim(a.floor)) = lower(trim(f.floor_code));

-- 2. Kiểm tra nếu vẫn còn khu vực đang hoạt động (deleted_at IS NULL) mà floor_id IS NULL -> RAISE EXCEPTION
DO $$
DECLARE
    v_missing_count INT;
    v_missing_list TEXT;
BEGIN
    SELECT count(*), string_agg(id || ' (' || name || ', ' || coalesce(building, 'NULL') || ' / ' || coalesce(floor, 'NULL') || ')', '; ')
    INTO v_missing_count, v_missing_list
    FROM areas
    WHERE deleted_at IS NULL AND floor_id IS NULL;

    IF v_missing_count > 0 THEN
        RAISE EXCEPTION 'V53 Migration thất bại: Vẫn còn % khu vực active có floor_id IS NULL: %', v_missing_count, v_missing_list;
    END IF;
END $$;

-- 3. Khóa NOT NULL cho cột floor_id (toàn bộ bản ghi, kể cả bản ghi soft-deleted đã được backfill)
ALTER TABLE areas ALTER COLUMN floor_id SET NOT NULL;

-- 4. Tạo partial unique index cho tên khu vực trên cùng một tầng đối với các khu vực đang hoạt động
CREATE UNIQUE INDEX ux_areas_floor_name_active
    ON areas (floor_id, lower(name))
    WHERE deleted_at IS NULL;
