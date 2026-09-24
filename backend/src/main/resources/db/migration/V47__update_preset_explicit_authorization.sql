-- ============================================================================
-- V47: Set explicit_authorization_required = true for CONFIDENTIAL_CONTACT_REQUIRED & HIGHLY_CONFIDENTIAL
-- ============================================================================

UPDATE area_level_presets
SET explicit_authorization_required = true,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL');

UPDATE areas
SET explicit_authorization_required = true,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL');
