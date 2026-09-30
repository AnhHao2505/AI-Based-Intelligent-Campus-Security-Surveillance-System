-- ============================================================================
-- V62: Step 6 — vô hiệu hoá / khôi phục khu vực (BR-AD-01..09, AD-Q4)
-- ============================================================================
-- Khu vực chỉ bị vô hiệu hoá mềm (is_active = false, deleted_at). Xoá cứng một dòng areas KHÔNG được
-- kéo theo sự cố, nhân sự chỉ định hay đơn truy cập: đổi các FK tới areas sang RESTRICT.
-- Tên constraint lấy từ information_schema trên DB test (29/09/2026):
--   security_incidents_area_id_fkey        CASCADE   -> RESTRICT
--   area_assigned_personnel_area_id_fkey   CASCADE   -> RESTRICT
--   access_requests_area_id_fkey           NO ACTION -> RESTRICT (đơn gắn khu vực qua access_requests.area_id;
--                                                       không có bảng access_request_areas)
-- Không đụng cameras, guest_*.

ALTER TABLE security_incidents
    DROP CONSTRAINT security_incidents_area_id_fkey,
    ADD CONSTRAINT security_incidents_area_id_fkey
        FOREIGN KEY (area_id) REFERENCES areas (id) ON DELETE RESTRICT;

ALTER TABLE area_assigned_personnel
    DROP CONSTRAINT area_assigned_personnel_area_id_fkey,
    ADD CONSTRAINT area_assigned_personnel_area_id_fkey
        FOREIGN KEY (area_id) REFERENCES areas (id) ON DELETE RESTRICT;

ALTER TABLE access_requests
    DROP CONSTRAINT access_requests_area_id_fkey,
    ADD CONSTRAINT access_requests_area_id_fkey
        FOREIGN KEY (area_id) REFERENCES areas (id) ON DELETE RESTRICT;

-- audit_logs (target_type, action) tham chiếu audit_event_types: thêm loại khôi phục khu vực (BR-AD-07).
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('AREA', 'RESTORE', 'AREA', 'Khôi phục khu vực đã vô hiệu hoá')
ON CONFLICT (target_type, action) DO NOTHING;
