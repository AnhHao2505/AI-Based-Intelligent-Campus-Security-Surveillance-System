-- ============================================================================
-- V65: Chuẩn hoá cờ explicit_authorization_required theo quy tắc nghiệp vụ bất biến
--
-- 1. LÝ DO / BỐI CẢNH:
-- Theo quy tắc nghiệp vụ cốt lõi của ICSS (TC-AA-08, BR-AC-02):
-- - PUBLIC và INTERNAL_CONFIDENTIAL: explicit_authorization_required LUÔN là FALSE
--   (người dùng đạt cấp độ truy cập tương ứng được phép ra vào tự do, không cần đơn xin phép hay chỉ định nhân sự).
-- - CONFIDENTIAL_CONTACT_REQUIRED và HIGHLY_CONFIDENTIAL: explicit_authorization_required LUÔN là TRUE
--   (bắt buộc phải có đơn xin cấp quyền đã được phê duyệt hoặc được phân công nhân sự trực thuộc mới được phép tiếp cận).
--
-- Do dữ liệu lịch sử hoặc thao tác can thiệp trước đây, bảng area_level_presets và một số bản ghi
-- trong bảng areas bị lệch cờ explicit so với loại khu vực tương ứng.
--
-- 2. CĂN CỨ:
-- - Quyết định kỹ thuật ngày 03/10/2026: Cờ explicit_authorization_required được suy ra bất biến từ loại khu vực (AreaLevel).
-- - Phê duyệt của Lucas ngày 04/10/2026: Thông qua Phương án A (chuẩn hoá dữ liệu qua migration V65 và lưu vết audit log hệ thống).
--
-- 3. MÔ TẢ TÁC ĐỘNG:
-- - Đối với các khu vực loại Liên hệ trước (CONFIDENTIAL_CONTACT_REQUIRED) hoặc Tuyệt mật (HIGHLY_CONFIDENTIAL)
--   đang bị tắt cờ (false): Migration sẽ bật cờ lên TRUE.
-- - Tác động nghiệp vụ: Người dùng dù có cấp độ truy cập phù hợp sẽ KHÔNG CÒN được ra vào thẳng,
--   mà bắt buộc phải tạo yêu cầu truy cập (Access Request) hoặc được gán nhân sự (Assigned Personnel).
-- - Ghi nhận nhật ký kiểm toán (audit_logs) với tác nhân SYSTEM cho mỗi khu vực và preset được đồng bộ.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Bước 1: Ghi nhận Audit Log cho bảng area_level_presets trước khi cập nhật
-- ----------------------------------------------------------------------------
INSERT INTO audit_logs (
    id,
    target_type,
    action,
    target_id,
    area_id,
    subject_user_id,
    old_value,
    new_value,
    reason,
    changed_by,
    changed_at,
    actor_type,
    actor_source,
    correlation_id
)
SELECT
    gen_random_uuid(),
    'LEVEL_PRESET',
    'UPDATE',
    p.area_level::text,
    NULL,
    NULL,
    jsonb_build_object(
        'areaAccessLevel', p.area_access_level,
        'explicitAuthorizationRequired', p.explicit_authorization_required
    ),
    jsonb_build_object(
        'areaAccessLevel', p.area_access_level,
        'explicitAuthorizationRequired', FALSE
    ),
    'Chuẩn hoá cờ preset theo loại khu vực (V65, Quyết định 03/10, Phương án A 04/10)',
    NULL,
    CURRENT_TIMESTAMP,
    'SYSTEM',
    'FLYWAY_MIGRATION_V65',
    gen_random_uuid()
FROM area_level_presets p
WHERE p.area_level IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL')
  AND p.explicit_authorization_required IS DISTINCT FROM FALSE;

INSERT INTO audit_logs (
    id,
    target_type,
    action,
    target_id,
    area_id,
    subject_user_id,
    old_value,
    new_value,
    reason,
    changed_by,
    changed_at,
    actor_type,
    actor_source,
    correlation_id
)
SELECT
    gen_random_uuid(),
    'LEVEL_PRESET',
    'UPDATE',
    p.area_level::text,
    NULL,
    NULL,
    jsonb_build_object(
        'areaAccessLevel', p.area_access_level,
        'explicitAuthorizationRequired', p.explicit_authorization_required
    ),
    jsonb_build_object(
        'areaAccessLevel', p.area_access_level,
        'explicitAuthorizationRequired', TRUE
    ),
    'Chuẩn hoá cờ preset theo loại khu vực (V65, Quyết định 03/10, Phương án A 04/10)',
    NULL,
    CURRENT_TIMESTAMP,
    'SYSTEM',
    'FLYWAY_MIGRATION_V65',
    gen_random_uuid()
FROM area_level_presets p
WHERE p.area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL')
  AND p.explicit_authorization_required IS DISTINCT FROM TRUE;

-- ----------------------------------------------------------------------------
-- Bước 2: Ghi nhận Audit Log cho từng khu vực trong bảng areas trước khi cập nhật
-- ----------------------------------------------------------------------------
INSERT INTO audit_logs (
    id,
    target_type,
    action,
    target_id,
    area_id,
    subject_user_id,
    old_value,
    new_value,
    reason,
    changed_by,
    changed_at,
    actor_type,
    actor_source,
    correlation_id
)
SELECT
    gen_random_uuid(),
    'AREA_ACCESS_RULES',
    'UPDATE',
    a.id::text,
    a.id,
    NULL,
    jsonb_build_object(
        'areaAccessLevel', a.area_access_level,
        'explicitAuthorizationRequired', a.explicit_authorization_required
    ),
    jsonb_build_object(
        'areaAccessLevel', a.area_access_level,
        'explicitAuthorizationRequired', FALSE
    ),
    'Chuẩn hoá cờ explicit theo loại khu vực (V65, Quyết định 03/10, Phương án A 04/10)',
    NULL,
    CURRENT_TIMESTAMP,
    'SYSTEM',
    'FLYWAY_MIGRATION_V65',
    gen_random_uuid()
FROM areas a
WHERE a.area_level IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL')
  AND a.explicit_authorization_required IS DISTINCT FROM FALSE;

INSERT INTO audit_logs (
    id,
    target_type,
    action,
    target_id,
    area_id,
    subject_user_id,
    old_value,
    new_value,
    reason,
    changed_by,
    changed_at,
    actor_type,
    actor_source,
    correlation_id
)
SELECT
    gen_random_uuid(),
    'AREA_ACCESS_RULES',
    'UPDATE',
    a.id::text,
    a.id,
    NULL,
    jsonb_build_object(
        'areaAccessLevel', a.area_access_level,
        'explicitAuthorizationRequired', a.explicit_authorization_required
    ),
    jsonb_build_object(
        'areaAccessLevel', a.area_access_level,
        'explicitAuthorizationRequired', TRUE
    ),
    'Chuẩn hoá cờ explicit theo loại khu vực (V65, Quyết định 03/10, Phương án A 04/10)',
    NULL,
    CURRENT_TIMESTAMP,
    'SYSTEM',
    'FLYWAY_MIGRATION_V65',
    gen_random_uuid()
FROM areas a
WHERE a.area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL')
  AND a.explicit_authorization_required IS DISTINCT FROM TRUE;

-- ----------------------------------------------------------------------------
-- Bước 3: Đồng bộ bảng area_level_presets
-- ----------------------------------------------------------------------------
UPDATE area_level_presets
SET explicit_authorization_required = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM FALSE;

UPDATE area_level_presets
SET explicit_authorization_required = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM TRUE;

-- ----------------------------------------------------------------------------
-- Bước 4: Đồng bộ bảng areas
-- ----------------------------------------------------------------------------
UPDATE areas
SET explicit_authorization_required = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM FALSE;

UPDATE areas
SET explicit_authorization_required = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM TRUE;
