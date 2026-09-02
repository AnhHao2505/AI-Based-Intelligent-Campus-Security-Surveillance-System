-- AreaService ghi audit log với UPDATE_GEOMETRY / DELETE_GEOMETRY (thêm ở B1),
-- nhưng CHECK khai ở V10 chỉ nhận CREATE/UPDATE/DEACTIVATE.
-- Hệ quả: PATCH /api/areas/{id}/geometry trả 500 khi ghi log.
ALTER TABLE area_change_logs
    DROP CONSTRAINT IF EXISTS area_change_logs_action_check;

ALTER TABLE area_change_logs
    ADD CONSTRAINT area_change_logs_action_check
    CHECK (action IN ('CREATE','UPDATE','DEACTIVATE','UPDATE_GEOMETRY','DELETE_GEOMETRY'));
