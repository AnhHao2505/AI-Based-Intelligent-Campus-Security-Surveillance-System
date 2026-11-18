-- ============================================================================
-- V133: Thời gian lưu ảnh + embedding khách sau khi hết quyền: mặc định 24 -> 72 giờ.
--   Nghiệp vụ (BR-GV-13, 27): khi quyền trong lượt còn hiệu lực thì không xoá; hết quyền (hết giờ lượt
--   hoặc bị thu hồi) thì giữ thêm GUEST_FACE_RETENTION_HOURS giờ rồi job mới xoá.
--   Chỉ đổi giá trị khi vẫn là mặc định cũ '24' (không ghi đè giá trị ADMIN đã sửa).
--   Bảng không có cột giá trị mặc định riêng (mặc định nằm ở ConfigKey.java). Khoảng cho phép giữ nguyên 0–720.
-- ============================================================================

UPDATE system_configurations SET config_value = '72'
WHERE config_key = 'GUEST_FACE_RETENTION_HOURS' AND config_value = '24';

UPDATE system_configurations
SET tooltip = 'Ảnh và đặc trưng khuôn mặt của khách được giữ thêm khoảng thời gian này kể từ khi lượt kết thúc hoặc bị thu hồi, sau đó hệ thống tự động xóa.'
WHERE config_key = 'GUEST_FACE_RETENTION_HOURS';
