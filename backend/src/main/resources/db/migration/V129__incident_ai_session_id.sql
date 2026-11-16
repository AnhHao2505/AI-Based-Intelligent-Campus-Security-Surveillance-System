-- V129: mã phiên AI của sự cố (roi_session_id do AI gửi kèm cảnh báo người lạ UNKNOWN_PERSON / PERSON_INTRUSION).
-- AI chốt cảnh báo người lạ rồi mới nhận ra mặt -> gửi IDENTITY_UPDATE cùng mã phiên này; Backend tìm đúng sự cố cũ
-- để cập nhật tên, lý do (người không có quyền) hoặc tự đóng (người có quyền), thay vì tạo thêm một sự cố thứ hai.
-- Sự cố tạo trước V129 và các loại cảnh báo khác để NULL.
ALTER TABLE security_incidents ADD COLUMN ai_session_id VARCHAR(64);

CREATE INDEX idx_security_incidents_ai_session_id
    ON security_incidents (ai_session_id)
    WHERE ai_session_id IS NOT NULL;
