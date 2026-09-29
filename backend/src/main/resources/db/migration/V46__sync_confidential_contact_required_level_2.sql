-- ============================================================================
-- V46: Synchronize default level for CONFIDENTIAL_CONTACT_REQUIRED to Level 2
-- ============================================================================

-- Cập nhật preset mặc định: CONFIDENTIAL_CONTACT_REQUIRED có level = 2
UPDATE area_level_presets
SET area_access_level = 2,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level = 'CONFIDENTIAL_CONTACT_REQUIRED';

-- Cập nhật các areas hiện có nếu đang dùng level = 3 do preset cũ
UPDATE areas
SET area_access_level = 2,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level = 'CONFIDENTIAL_CONTACT_REQUIRED'
  AND area_access_level = 3;
