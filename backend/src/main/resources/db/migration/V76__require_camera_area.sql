-- Every camera must belong to exactly one area.
-- Remove legacy orphan cameras first; their camera-owned stream and health rows
-- are removed by the existing ON DELETE CASCADE foreign keys.
DELETE FROM cameras
WHERE area_id IS NULL;

ALTER TABLE cameras
    ALTER COLUMN area_id SET NOT NULL;

-- Deleting an area must not silently detach its cameras.
ALTER TABLE cameras
    DROP CONSTRAINT IF EXISTS cameras_area_id_fkey,
    ADD CONSTRAINT fk_cameras_area_id
        FOREIGN KEY (area_id) REFERENCES areas(id) ON DELETE RESTRICT;
