-- ============================================================================
-- V136: ACCESS_LEVEL_DEFAULT_NORMAL_USER chỉ nhận 1–2 (BR-AL-33)
--
-- Bối cảnh:
--   - BR-AL-33: người dùng thường được cấp ban đầu 1 hoặc 2 khi tạo; cấp 3 chỉ do FM nâng về sau. Cấp mặc định
--     (dùng khi Admin không chọn) vì thế cũng phải nằm trong 1–2.
--   - V38 khai báo min_value 1, max_value 3. Hạ max_value xuống 2 thì cơ chế sẵn có tự áp dụng:
--       * PATCH /api/system-configurations/{key}: SystemConfigService.validateValue từ chối > max_value (400);
--       * đọc khi tạo tài khoản: SystemConfigService.getIntWithinBounds -> ngoài khoảng thì WARN + mặc định của ConfigKey (1).
--
-- Ràng buộc:
--   - KHÔNG đổi config_value: DB đang có 3 (dữ liệu cũ) vẫn giữ nguyên; backend dùng 1 khi tạo tài khoản và ghi WARN,
--     Admin sửa lại trên màn Cấu hình hệ thống. Không đổi cấp của tài khoản đã có.
--   - Chỉ đổi metadata, không ghi system_configuration_change_logs (change log ghi đổi giá trị, không ghi metadata).
--   - Chạy lại không lỗi.
-- ============================================================================

UPDATE system_configurations
SET max_value = 2
WHERE config_key = 'ACCESS_LEVEL_DEFAULT_NORMAL_USER'
  AND max_value IS DISTINCT FROM 2;
