-- ============================================================================
-- V41: Restore Invariant Area Level Presets
-- ============================================================================

-- Upsert the 4 area level presets with invariant default values:
--   PUBLIC                        → 1, false
--   INTERNAL_CONFIDENTIAL         → 2, false
--   CONFIDENTIAL_CONTACT_REQUIRED → 3, false
--   HIGHLY_CONFIDENTIAL           → 3, true
-- Note: Do NOT modify existing areas table rows.

INSERT INTO area_level_presets (area_level, area_access_level, explicit_authorization_required, updated_at)
VALUES
    ('PUBLIC', 1, false, CURRENT_TIMESTAMP),
    ('INTERNAL_CONFIDENTIAL', 2, false, CURRENT_TIMESTAMP),
    ('CONFIDENTIAL_CONTACT_REQUIRED', 3, false, CURRENT_TIMESTAMP),
    ('HIGHLY_CONFIDENTIAL', 3, true, CURRENT_TIMESTAMP)
ON CONFLICT (area_level) DO UPDATE SET
    area_access_level = EXCLUDED.area_access_level,
    explicit_authorization_required = EXCLUDED.explicit_authorization_required,
    updated_at = CURRENT_TIMESTAMP;
