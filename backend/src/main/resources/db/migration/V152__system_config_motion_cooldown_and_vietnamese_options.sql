-- ============================================================================
-- V152: Chuẩn hoá cấu hình hệ thống (tách từ V151 để giữ nguyên checksum V151)
-- 1. Nâng thời gian báo lại chuyển động lạ (AI_SUSPICIOUS_MOTION_COOLDOWN_SECONDS) từ 5 -> 30 giây
-- 2. Giữ data_type = 'STRING' cho 2 khoá enum đơn trị (ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES,
--    INCIDENT_SEVERITY_DEFAULT) và đồng bộ câu tooltip sang chuẩn nhãn tiếng Việt
-- ============================================================================

UPDATE system_configurations
SET config_value = '30',
    updated_at   = CURRENT_TIMESTAMP
WHERE config_key = 'AI_SUSPICIOUS_MOTION_COOLDOWN_SECONDS'
  AND config_value = '5';

UPDATE system_configurations
SET tooltip = 'Loại khu vực cho phép người tạo đơn nhóm bảo lãnh thành viên chưa đủ cấp truy cập (chỉ áp dụng cho khu vực Bảo mật; không áp dụng cho Công khai và Bảo mật cao).'
WHERE config_key = 'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES';

UPDATE system_configurations
SET tooltip = 'Mức độ được gán cho sự cố nếu không khớp quy tắc riêng nào (Trung bình, Cao hoặc Nghiêm trọng).'
WHERE config_key = 'INCIDENT_SEVERITY_DEFAULT';
