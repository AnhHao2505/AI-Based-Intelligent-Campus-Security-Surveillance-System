-- V23: Drop camera hardware angle columns and camera_specifications table
-- As per design decisions (hardware specs documented in docs, not in DB/API)

ALTER TABLE cameras DROP COLUMN IF EXISTS mounting_height;
ALTER TABLE cameras DROP COLUMN IF EXISTS orientation;
ALTER TABLE cameras DROP COLUMN IF EXISTS tilt_angle;

DROP TABLE IF EXISTS camera_specifications CASCADE;
