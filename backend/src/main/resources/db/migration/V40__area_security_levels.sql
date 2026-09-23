-- ============================================================================
-- V40: Refactor Area Security Levels & Extend Request Status to FINISHED
-- ============================================================================

-- 1. Update check constraint on areas table
ALTER TABLE areas DROP CONSTRAINT IF EXISTS chk_areas_area_level;

-- Migrate existing areas FIRST before adding the new check constraint
UPDATE areas SET area_level = 'INTERNAL_CONFIDENTIAL', explicit_authorization_required = false WHERE area_level = 'SEMI_PRIVATE';
UPDATE areas SET area_level = 'HIGHLY_CONFIDENTIAL', explicit_authorization_required = true WHERE area_level = 'PRIVATE';

ALTER TABLE areas ADD CONSTRAINT chk_areas_area_level
    CHECK (area_level IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL', 'CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL'));

-- 2. Update check constraint on area_level_presets table
ALTER TABLE area_level_presets DROP CONSTRAINT IF EXISTS chk_preset_area_level;

DELETE FROM area_level_presets
WHERE area_level IN ('SEMI_PRIVATE', 'PRIVATE');

ALTER TABLE area_level_presets ADD CONSTRAINT chk_preset_area_level
CHECK (area_level IN (
    'PUBLIC',
    'INTERNAL_CONFIDENTIAL',
    'CONFIDENTIAL_CONTACT_REQUIRED',
    'HIGHLY_CONFIDENTIAL'
));

-- Upsert the 4 area level presets
INSERT INTO area_level_presets (area_level, area_access_level, explicit_authorization_required, updated_at)
VALUES
    ('PUBLIC', 1, false, CURRENT_TIMESTAMP),
    ('INTERNAL_CONFIDENTIAL', 2, false, CURRENT_TIMESTAMP),
    ('CONFIDENTIAL_CONTACT_REQUIRED', 2, true, CURRENT_TIMESTAMP),
    ('HIGHLY_CONFIDENTIAL', 3, true, CURRENT_TIMESTAMP)
ON CONFLICT (area_level) DO UPDATE SET
    area_access_level = EXCLUDED.area_access_level,
    explicit_authorization_required = EXCLUDED.explicit_authorization_required,
    updated_at = CURRENT_TIMESTAMP;

-- 3. Extend access_requests status to include FINISHED
ALTER TABLE access_requests DROP CONSTRAINT IF EXISTS chk_access_requests_status;
ALTER TABLE access_requests ADD CONSTRAINT chk_access_requests_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'FINISHED'));
