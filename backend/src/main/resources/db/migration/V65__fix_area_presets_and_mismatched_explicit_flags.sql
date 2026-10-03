-- ============================================================================
-- V65: Chuẩn hoá cờ explicit_authorization_required theo quy tắc nghiệp vụ bất biến
--
-- Lý do / Bối cảnh:
-- Theo quy tắc nghiệp vụ phân quyền khu vực (TC-AA-08, BR-AC-02):
-- 1. PUBLIC và INTERNAL_CONFIDENTIAL: explicit_authorization_required LUÔN là FALSE
--    (cho phép người có cấp độ truy cập phù hợp tự do tiếp cận, không cần đơn xin phép).
-- 2. CONFIDENTIAL_CONTACT_REQUIRED và HIGHLY_CONFIDENTIAL: explicit_authorization_required LUÔN là TRUE
--    (bắt buộc phải có đơn xin cấp quyền hoặc chỉ định nhân sự AP mới được phép truy cập).
--
-- Dữ liệu lịch sử hoặc thao tác can thiệp có thể làm cờ explicit_authorization_required
-- của preset hoặc của một số khu vực bị lệch so với loại khu vực (VD: khu vực CONTACT
-- nhưng cờ = false, hoặc preset INTERNAL có cờ = true).
-- Migration này đồng bộ lại cờ của bảng area_level_presets và bảng areas về đúng quy tắc bất biến.
-- ============================================================================

-- 1. Đồng bộ bảng area_level_presets
UPDATE area_level_presets
SET explicit_authorization_required = false,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM false;

UPDATE area_level_presets
SET explicit_authorization_required = true,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM true;

-- 2. Đồng bộ bảng areas cho các khu vực có cờ lệch so với loại khu vực
UPDATE areas
SET explicit_authorization_required = false,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('PUBLIC', 'INTERNAL_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM false;

UPDATE areas
SET explicit_authorization_required = true,
    updated_at = CURRENT_TIMESTAMP
WHERE area_level IN ('CONFIDENTIAL_CONTACT_REQUIRED', 'HIGHLY_CONFIDENTIAL')
  AND explicit_authorization_required IS DISTINCT FROM true;
