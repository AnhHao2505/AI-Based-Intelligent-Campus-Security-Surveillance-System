DROP INDEX IF EXISTS idx_face_data_full_name;
ALTER TABLE face_data DROP COLUMN IF EXISTS full_name;
