-- ============================================================================
-- V83: Gộp loại khu vực 4 → 3, loại ⇔ cấp, bỏ cờ chỉ định đích danh (quyết định nhóm + GVHD 19/10/2026)
--
--   PUBLIC                         (cấp 1)  giữ nguyên        "Công khai"
--   INTERNAL_CONFIDENTIAL          (cấp 2) ┐
--   CONFIDENTIAL_CONTACT_REQUIRED  (cấp 2) ┴→ CONFIDENTIAL    "Bảo mật" (cấp 2)
--   HIGHLY_CONFIDENTIAL            (cấp 3)  giữ nguyên        "Tuyệt mật"
--
-- Quy tắc mới (code): cấp người ≥ cấp của loại thì vào thẳng (ACCESS_LEVEL); cấp khu luôn bằng cấp của loại;
-- không còn sửa cấp riêng từng khu hay sửa preset; cờ explicit_authorization_required không còn dùng.
--
-- Ràng buộc:
--   - Merge CÙNG commit với enum AreaLevel 3 giá trị (Hibernate @Enumerated STRING đọc giá trị lạ sẽ lỗi).
--   - audit_logs chỉ cho INSERT (trigger trg_audit_logs_append_only, V55): audit / snapshot cũ GIỮ NGUYÊN tên loại cũ
--     (là lịch sử); frontend hiển thị nhãn cho cả tên cũ.
--   - KHÔNG đổi cấp của tài khoản đang có (không UPDATE users): cấp mặc định mới chỉ áp cho tài khoản tạo sau V83.
--   - Viết để chạy lại không lỗi (DROP CONSTRAINT IF EXISTS trước khi ADD, các bước UPDATE / INSERT có điều kiện).
--   - pg_dump DB dev trước khi chạy (CLAUDE.local.md §3). Câu SELECT kiểm sau khi chạy ở Bước 9.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- Bước 0. Dừng sớm nếu areas có giá trị loại lạ (dữ liệu tay, nhánh khác)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM areas
               WHERE area_level NOT IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL', 'CONFIDENTIAL_CONTACT_REQUIRED',
                                        'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')) THEN
        RAISE EXCEPTION 'V83: areas có area_level ngoài các loại đã biết, dừng migration';
    END IF;
END $$;


-- ----------------------------------------------------------------------------
-- Bước 1. Audit TRƯỚC khi đổi (mẫu V67: actor SYSTEM, changed_by NULL). Mọi dòng dùng chung một correlation_id
--         (md5 cố định của tên nguồn) để màn nhật ký gom được theo một thao tác.
-- ----------------------------------------------------------------------------

-- 1a. Khu đổi loại sang Bảo mật: AREA / CHANGE_TYPE (event type có từ V56)
INSERT INTO audit_logs (id, target_type, action, target_id, area_id, subject_user_id,
                        old_value, new_value, reason, changed_by, changed_at,
                        actor_type, actor_source, correlation_id)
SELECT gen_random_uuid(), 'AREA', 'CHANGE_TYPE', a.id::text, a.id, NULL,
       jsonb_build_object('areaLevel', a.area_level,
                          'areaAccessLevel', a.area_access_level,
                          'explicitAuthorizationRequired', a.explicit_authorization_required),
       jsonb_build_object('areaLevel', 'CONFIDENTIAL',
                          'areaAccessLevel', 2,
                          'explicitAuthorizationRequired', FALSE),
       'Gộp loại khu vực 4 → 3: Nội bộ và Liên hệ trước thành Bảo mật (V83, 19/10/2026)',
       NULL, CURRENT_TIMESTAMP, 'SYSTEM', 'FLYWAY_MIGRATION_V83', md5('FLYWAY_MIGRATION_V83')::uuid
FROM areas a
WHERE a.area_level IN ('INTERNAL_CONFIDENTIAL', 'CONFIDENTIAL_CONTACT_REQUIRED');

-- 1b. Khu giữ loại nhưng đổi quy tắc: cấp lệch với loại (ADMIN từng sửa riêng) hoặc còn cờ explicit = true.
--     AREA_ACCESS_RULES / UPDATE (event type có từ V55).
INSERT INTO audit_logs (id, target_type, action, target_id, area_id, subject_user_id,
                        old_value, new_value, reason, changed_by, changed_at,
                        actor_type, actor_source, correlation_id)
SELECT gen_random_uuid(), 'AREA_ACCESS_RULES', 'UPDATE', a.id::text, a.id, NULL,
       jsonb_build_object('areaAccessLevel', a.area_access_level,
                          'explicitAuthorizationRequired', a.explicit_authorization_required),
       jsonb_build_object('areaAccessLevel', CASE a.area_level WHEN 'PUBLIC' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 ELSE 3 END,
                          'explicitAuthorizationRequired', FALSE),
       'Loại ⇔ cấp, bỏ cờ chỉ định đích danh (V83, 19/10/2026)',
       NULL, CURRENT_TIMESTAMP, 'SYSTEM', 'FLYWAY_MIGRATION_V83', md5('FLYWAY_MIGRATION_V83')::uuid
FROM areas a
WHERE a.area_level IN ('PUBLIC', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')
  AND (a.area_access_level IS DISTINCT FROM CASE a.area_level WHEN 'PUBLIC' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 ELSE 3 END
       OR a.explicit_authorization_required IS DISTINCT FROM FALSE);

-- 1c. Preset: LEVEL_PRESET / UPDATE cho mọi dòng sẽ đổi (đổi tên loại, đổi cấp, tắt cờ). target_id giữ tên loại cũ.
--     Dòng CONFIDENTIAL_CONTACT_REQUIRED bị gộp vào CONFIDENTIAL (xoá ở Bước 6): ghi bằng UPDATE vì chưa có
--     event type LEVEL_PRESET / DELETE (AuditAction không có DELETE, thêm vào sẽ làm màn nhật ký không đọc được dòng).
INSERT INTO audit_logs (id, target_type, action, target_id, area_id, subject_user_id,
                        old_value, new_value, reason, changed_by, changed_at,
                        actor_type, actor_source, correlation_id)
SELECT gen_random_uuid(), 'LEVEL_PRESET', 'UPDATE', p.area_level, NULL, NULL,
       jsonb_build_object('areaLevel', p.area_level,
                          'areaAccessLevel', p.area_access_level,
                          'explicitAuthorizationRequired', p.explicit_authorization_required),
       jsonb_build_object('areaLevel', CASE WHEN p.area_level IN ('INTERNAL_CONFIDENTIAL', 'CONFIDENTIAL_CONTACT_REQUIRED')
                                            THEN 'CONFIDENTIAL' ELSE p.area_level END,
                          'areaAccessLevel', CASE p.area_level WHEN 'PUBLIC' THEN 1 WHEN 'HIGHLY_CONFIDENTIAL' THEN 3 ELSE 2 END,
                          'explicitAuthorizationRequired', FALSE),
       'Gộp loại khu vực 4 → 3, cấp cố định theo loại, bảng chỉ để xem (V83, 19/10/2026)',
       NULL, CURRENT_TIMESTAMP, 'SYSTEM', 'FLYWAY_MIGRATION_V83', md5('FLYWAY_MIGRATION_V83')::uuid
FROM area_level_presets p
WHERE p.area_level IN ('INTERNAL_CONFIDENTIAL', 'CONFIDENTIAL_CONTACT_REQUIRED')
   OR p.area_access_level IS DISTINCT FROM CASE p.area_level WHEN 'PUBLIC' THEN 1 WHEN 'HIGHLY_CONFIDENTIAL' THEN 3 ELSE 2 END
   OR p.explicit_authorization_required IS DISTINCT FROM FALSE;


-- ----------------------------------------------------------------------------
-- Bước 2. Bỏ CHECK cũ của area_level (4 loại, V40)
-- ----------------------------------------------------------------------------
ALTER TABLE areas DROP CONSTRAINT IF EXISTS chk_areas_area_level;
ALTER TABLE areas DROP CONSTRAINT IF EXISTS chk_areas_level_matches_type;
ALTER TABLE areas DROP CONSTRAINT IF EXISTS chk_areas_explicit_unused;


-- ----------------------------------------------------------------------------
-- Bước 3. Nội bộ + Liên hệ trước → Bảo mật. Mọi dòng areas đổi thật đều tăng version (BR-TC-13: tab đang mở nhận 409).
-- ----------------------------------------------------------------------------
UPDATE areas
SET area_level = 'CONFIDENTIAL', area_access_level = 2, explicit_authorization_required = FALSE,
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('INTERNAL_CONFIDENTIAL', 'CONFIDENTIAL_CONTACT_REQUIRED');


-- ----------------------------------------------------------------------------
-- Bước 4. Cấp của khu = cấp của loại (1/2/3) cho mọi khu; cờ explicit = false cho mọi khu
-- ----------------------------------------------------------------------------
UPDATE areas
SET area_access_level = CASE area_level WHEN 'PUBLIC' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 ELSE 3 END,
    explicit_authorization_required = FALSE,
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE area_access_level IS DISTINCT FROM CASE area_level WHEN 'PUBLIC' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 ELSE 3 END
   OR explicit_authorization_required IS DISTINCT FROM FALSE;

-- Giữ cột cờ (frontend / mobile còn đọc), mặc định false, khoá bằng CHECK để không ai bật lại. Xoá cột ở migration sau.
ALTER TABLE areas ALTER COLUMN explicit_authorization_required SET DEFAULT FALSE;
COMMENT ON COLUMN areas.explicit_authorization_required IS
    'Không dùng từ V83 (19/10/2026), luôn FALSE. Chờ xoá ở migration sau khi frontend / mobile hết đọc.';
COMMENT ON COLUMN areas.area_access_level IS
    'Từ V83 luôn bằng cấp của loại (PUBLIC 1, CONFIDENTIAL 2, HIGHLY_CONFIDENTIAL 3), giữ bằng CHECK chk_areas_level_matches_type.';


-- ----------------------------------------------------------------------------
-- Bước 5. CHECK mới của areas: 3 loại; loại ⇔ cấp; cờ explicit luôn false
--         Cột area_access_level DEFAULT 3 (V38) giữ nguyên: INSERT thiếu cấp ở loại khác Tuyệt mật sẽ vướng CHECK, đúng ý
--         (entity Area tự đặt cấp theo loại trước khi ghi).
-- ----------------------------------------------------------------------------
ALTER TABLE areas ADD CONSTRAINT chk_areas_area_level
    CHECK (area_level IN ('PUBLIC', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'));
ALTER TABLE areas ADD CONSTRAINT chk_areas_level_matches_type CHECK (
    (area_level = 'PUBLIC'              AND area_access_level = 1) OR
    (area_level = 'CONFIDENTIAL'        AND area_access_level = 2) OR
    (area_level = 'HIGHLY_CONFIDENTIAL' AND area_access_level = 3)
);
ALTER TABLE areas ADD CONSTRAINT chk_areas_explicit_unused CHECK (explicit_authorization_required = FALSE);


-- ----------------------------------------------------------------------------
-- Bước 6. area_level_presets: đúng 3 dòng, cấp 1/2/3, cờ false. Bảng chỉ để xem (PUT / PATCH preset trả 400 ERR_AC_005).
-- ----------------------------------------------------------------------------
ALTER TABLE area_level_presets DROP CONSTRAINT IF EXISTS chk_preset_area_level;
ALTER TABLE area_level_presets DROP CONSTRAINT IF EXISTS chk_preset_level_matches_type;
ALTER TABLE area_level_presets DROP CONSTRAINT IF EXISTS chk_preset_explicit_unused;

DELETE FROM area_level_presets WHERE area_level = 'CONFIDENTIAL_CONTACT_REQUIRED';
UPDATE area_level_presets SET area_level = 'CONFIDENTIAL'
WHERE area_level = 'INTERNAL_CONFIDENTIAL'
  AND NOT EXISTS (SELECT 1 FROM area_level_presets WHERE area_level = 'CONFIDENTIAL');
DELETE FROM area_level_presets WHERE area_level = 'INTERNAL_CONFIDENTIAL';

-- Thiếu dòng nào thì thêm (DB thiếu preset trước đây rơi vào fallback cấp 3 của code)
INSERT INTO area_level_presets (area_level, area_access_level, explicit_authorization_required, updated_at)
VALUES ('PUBLIC', 1, FALSE, CURRENT_TIMESTAMP),
       ('CONFIDENTIAL', 2, FALSE, CURRENT_TIMESTAMP),
       ('HIGHLY_CONFIDENTIAL', 3, FALSE, CURRENT_TIMESTAMP)
ON CONFLICT (area_level) DO NOTHING;

UPDATE area_level_presets
SET area_access_level = CASE area_level WHEN 'PUBLIC' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 ELSE 3 END,
    explicit_authorization_required = FALSE,
    updated_by = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE area_access_level IS DISTINCT FROM CASE area_level WHEN 'PUBLIC' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 ELSE 3 END
   OR explicit_authorization_required IS DISTINCT FROM FALSE;

ALTER TABLE area_level_presets ADD CONSTRAINT chk_preset_area_level
    CHECK (area_level IN ('PUBLIC', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'));
ALTER TABLE area_level_presets ADD CONSTRAINT chk_preset_level_matches_type CHECK (
    (area_level = 'PUBLIC'              AND area_access_level = 1) OR
    (area_level = 'CONFIDENTIAL'        AND area_access_level = 2) OR
    (area_level = 'HIGHLY_CONFIDENTIAL' AND area_access_level = 3)
);
ALTER TABLE area_level_presets ADD CONSTRAINT chk_preset_explicit_unused CHECK (explicit_authorization_required = FALSE);

-- Không bảng nào khác lưu tên loại (đã grep: lịch sự kiện, đơn, lượt khách, nhật ký ra vào chỉ lưu area_id).
-- audit_logs, notifications, system_configuration_change_logs còn tên / nhãn cũ: là lịch sử, KHÔNG sửa.


-- ----------------------------------------------------------------------------
-- Bước 7. Cấu hình hệ thống. Mỗi giá trị đổi thật ghi 1 dòng system_configuration_change_logs (changed_by NULL = hệ thống)
--         TRƯỚC khi UPDATE để giữ giá trị cũ.
-- ----------------------------------------------------------------------------

-- 7a. Khoảng cách cấp tối đa của đơn cá nhân (mới): cấp khu − cấp người ∈ [1, N]
INSERT INTO system_configurations (config_key, config_value, data_type, min_value, max_value, unit,
                                   config_group, display_order, description, editable)
VALUES ('ACCESS_REQUEST_MAX_LEVEL_GAP', '1', 'INTEGER', 1, 2, 'cấp', 'ACCESS_REQUEST', 7,
        'Đơn cá nhân chỉ xin được khu vực có cấp cao hơn cấp của người tạo đơn tối đa bấy nhiêu cấp (1: cấp 1 xin Bảo mật, cấp 2 xin Tuyệt mật; 2: cấp 1 xin được cả Tuyệt mật)',
        true)
ON CONFLICT (config_key) DO NOTHING;

-- 7b. Danh sách loại cho phép bảo lãnh thành viên đơn nhóm (V68): sau gộp chỉ còn đúng một giá trị hợp lệ
INSERT INTO system_configuration_change_logs (config_key, old_value, new_value, changed_by, changed_at, reason)
SELECT config_key, config_value, 'CONFIDENTIAL', NULL, CURRENT_TIMESTAMP,
       'Gộp loại khu vực 4 → 3: chỉ khu Bảo mật cho bảo lãnh thành viên đơn nhóm (V83, 19/10/2026)'
FROM system_configurations
WHERE config_key = 'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES'
  AND config_value IS DISTINCT FROM 'CONFIDENTIAL';
UPDATE system_configurations
SET config_value = 'CONFIDENTIAL', updated_by = NULL, updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES'
  AND config_value IS DISTINCT FROM 'CONFIDENTIAL';
UPDATE system_configurations
SET description = 'Danh sách loại khu vực cho phép bảo lãnh thành viên chưa đủ cấp trong đơn nhóm (phân tách bởi dấu phẩy). Từ V83 chỉ nhận CONFIDENTIAL (Bảo mật)'
WHERE config_key = 'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES';

-- 7c. Mô tả cũ còn ghi "PRIVATE" (V32). Giữ key để khỏi đổi code, chỉ sửa mô tả.
UPDATE system_configurations
SET description = 'Cho phép tạo yêu cầu truy cập nhóm tại khu vực Tuyệt mật (HIGHLY_CONFIDENTIAL)'
WHERE config_key = 'ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE';

-- 7d. BR-AL-24: cấp mặc định khi TẠO MỚI tài khoản FACILITY_MANAGER và ADMIN = 3 (NORMAL_USER 1, GUARD 2 giữ nguyên).
--     KHÔNG UPDATE users: tài khoản đang có giữ nguyên cấp, Lucas tự chỉnh tay nếu cần.
INSERT INTO system_configuration_change_logs (config_key, old_value, new_value, changed_by, changed_at, reason)
SELECT config_key, config_value, '3', NULL, CURRENT_TIMESTAMP,
       'Cấp mặc định khi tạo mới tài khoản = 3, không đổi tài khoản đang có (V83, 19/10/2026)'
FROM system_configurations
WHERE config_key IN ('ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER', 'ACCESS_LEVEL_DEFAULT_ADMIN')
  AND config_value IS DISTINCT FROM '3';
UPDATE system_configurations
SET config_value = '3', updated_by = NULL, updated_at = CURRENT_TIMESTAMP
WHERE config_key IN ('ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER', 'ACCESS_LEVEL_DEFAULT_ADMIN')
  AND config_value IS DISTINCT FROM '3';


-- ----------------------------------------------------------------------------
-- Bước 8. Không làm: đổi cấp users, sửa audit / thông báo cũ, xoá cột explicit_authorization_required (migration sau).
-- ----------------------------------------------------------------------------


-- ----------------------------------------------------------------------------
-- Bước 9. Kiểm sau khi chạy (chỉ để người chạy tay đối chiếu, Flyway không cần)
-- ----------------------------------------------------------------------------
-- SELECT area_level, area_access_level, explicit_authorization_required, count(*) FROM areas GROUP BY 1, 2, 3 ORDER BY 1;
--   -> chỉ còn (PUBLIC,1,f) (CONFIDENTIAL,2,f) (HIGHLY_CONFIDENTIAL,3,f)
-- SELECT area_level, area_access_level, explicit_authorization_required FROM area_level_presets ORDER BY area_access_level;
--   -> đúng 3 dòng
-- SELECT conname FROM pg_constraint WHERE conname IN ('chk_areas_area_level', 'chk_areas_level_matches_type',
--   'chk_areas_explicit_unused', 'chk_preset_area_level', 'chk_preset_level_matches_type', 'chk_preset_explicit_unused');
--   -> 6 dòng
-- SELECT config_key, config_value FROM system_configurations WHERE config_key IN ('ACCESS_REQUEST_MAX_LEVEL_GAP',
--   'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES', 'ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER', 'ACCESS_LEVEL_DEFAULT_ADMIN');
--   -> 1, CONFIDENTIAL, 3, 3
-- SELECT target_type, action, count(*) FROM audit_logs WHERE actor_source = 'FLYWAY_MIGRATION_V83' GROUP BY 1, 2;
-- SELECT config_key, old_value, new_value FROM system_configuration_change_logs WHERE reason LIKE '%(V83,%';
-- SELECT role, access_level, count(*) FROM users GROUP BY 1, 2 ORDER BY 1, 2;   -- so với trước khi chạy: không đổi
