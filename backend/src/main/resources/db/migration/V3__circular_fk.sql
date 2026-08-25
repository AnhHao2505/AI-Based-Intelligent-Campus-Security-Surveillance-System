-- ============================================================================
-- V3: Circular FK — Thêm FK vòng detection_events.incident_id → security_incidents.id
-- Không thể khai báo trong V2 vì tại thời điểm tạo detection_events,
-- bảng security_incidents chưa tồn tại.
-- ============================================================================

ALTER TABLE detection_events
    ADD CONSTRAINT fk_detection_events_incident
    FOREIGN KEY (incident_id) REFERENCES security_incidents(id);
