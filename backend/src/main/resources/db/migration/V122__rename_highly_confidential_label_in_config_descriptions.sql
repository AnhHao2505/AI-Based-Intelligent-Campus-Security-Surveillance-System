-- ============================================================================
-- V122: Đổi nhãn loại khu vực cấp 3 trong mô tả cấu hình hệ thống sang "Bảo mật cao" (quyết định nhóm 07/11/2026).
--
-- V83 ghi mô tả của 2 cấu hình bằng nhãn cũ; mô tả hiển thị cho ADMIN ở trang cấu hình hệ thống.
-- Không sửa V83 (đổi checksum), chỉ cập nhật dữ liệu. Chỉ đổi mô tả, không đổi giá trị nên không ghi
-- system_configuration_change_logs. Enum HIGHLY_CONFIDENTIAL và key cấu hình giữ nguyên.
-- Chạy lại không đổi gì thêm.
-- ============================================================================

UPDATE system_configurations
SET description = 'Đơn cá nhân chỉ xin được khu vực có cấp cao hơn cấp của người tạo đơn tối đa bấy nhiêu cấp (1: cấp 1 xin Bảo mật, cấp 2 xin Bảo mật cao; 2: cấp 1 xin được cả Bảo mật cao)'
WHERE config_key = 'ACCESS_REQUEST_MAX_LEVEL_GAP';

UPDATE system_configurations
SET description = 'Cho phép tạo yêu cầu truy cập nhóm tại khu vực Bảo mật cao (HIGHLY_CONFIDENTIAL)'
WHERE config_key = 'ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE';
