-- Migration V50: Remove code column and related unique indexes from areas table
DROP INDEX IF EXISTS ux_areas_code;
DROP INDEX IF EXISTS uq_areas_code_active;
ALTER TABLE areas DROP CONSTRAINT IF EXISTS areas_code_key;
ALTER TABLE areas DROP COLUMN IF EXISTS code;
