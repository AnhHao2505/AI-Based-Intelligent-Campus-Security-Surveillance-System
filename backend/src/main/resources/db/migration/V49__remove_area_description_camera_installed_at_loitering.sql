-- Migration V49: Remove Area description, Camera installed_at, and Loitering threshold
ALTER TABLE areas DROP COLUMN IF EXISTS description;
ALTER TABLE areas DROP COLUMN IF EXISTS loitering_threshold_seconds;

ALTER TABLE cameras DROP COLUMN IF EXISTS installed_at;
