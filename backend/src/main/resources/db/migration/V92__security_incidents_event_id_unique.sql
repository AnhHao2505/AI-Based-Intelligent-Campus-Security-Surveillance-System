-- ============================================================================
-- V92: Chống tạo trùng sự cố theo event_id (AI gửi lại cùng sự kiện qua MQTT / REST)
-- ============================================================================
-- SecurityIncidentService.ingestIncident đã trả sự cố cũ khi trùng event_id; index này chặn trường hợp
-- hai tin trùng đến cùng lúc cùng lọt qua bước kiểm tra đó.
-- Chỉ ràng buộc event_id có giá trị: NULL và chuỗi rỗng vẫn tạo được nhiều sự cố như trước.
-- Không xoá hay sửa dữ liệu.
CREATE UNIQUE INDEX IF NOT EXISTS uq_security_incidents_event_id
    ON security_incidents (event_id)
    WHERE event_id IS NOT NULL AND btrim(event_id) <> '';
