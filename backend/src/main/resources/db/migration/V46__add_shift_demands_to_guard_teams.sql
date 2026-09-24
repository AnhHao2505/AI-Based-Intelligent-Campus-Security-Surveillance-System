-- ============================================================================
-- V46: Add Shift Demand Configuration to Guard Teams
-- ============================================================================

ALTER TABLE guard_teams 
    ADD COLUMN IF NOT EXISTS weekday_morning_demand INT DEFAULT 3,
    ADD COLUMN IF NOT EXISTS weekday_afternoon_demand INT DEFAULT 4,
    ADD COLUMN IF NOT EXISTS weekday_night_demand INT DEFAULT 2,
    ADD COLUMN IF NOT EXISTS sunday_morning_demand INT DEFAULT 2,
    ADD COLUMN IF NOT EXISTS sunday_afternoon_demand INT DEFAULT 2,
    ADD COLUMN IF NOT EXISTS sunday_night_demand INT DEFAULT 2,
    ADD COLUMN IF NOT EXISTS has_sunday_custom BOOLEAN DEFAULT TRUE;
