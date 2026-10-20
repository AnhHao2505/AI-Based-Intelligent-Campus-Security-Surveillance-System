-- V82: độ dài lý do thao tác vào cấu hình hệ thống (checklist 6.1, bỏ 10/500 viết cứng ở DTO, service, FE).
-- Áp cho: huỷ / từ chối / hoàn thành đơn, đổi loại / vô hiệu hoá / khôi phục khu vực, ghi chú chế độ sự kiện
-- và lịch sự kiện, quy tắc khu vực, nhân sự chỉ định, cấp truy cập, preset, sửa cấu hình, lý do lượt khách.
-- Đo sau khi trim. Cột nhỏ nhất đang lưu lý do là VARCHAR(500): audit_logs.reason (V42), mọi lý do đều vào đây;
-- system_configuration_change_logs.reason (V55), area_event_schedules.note / cancel_note (V55_1),
-- guest_visits.review_reason / revoke_reason / cancel_reason (V60) -> REASON_MAX_LENGTH tối đa 500.
-- min < max kiểm thêm ở SystemConfigService khi ADMIN sửa. Cùng nhóm SECURITY (display_order 40–55 đã dùng).
INSERT INTO system_configurations (
    config_key,
    config_value,
    data_type,
    min_value,
    max_value,
    unit,
    config_group,
    display_order,
    description,
    editable
) VALUES
    ('REASON_MIN_LENGTH', '10', 'INTEGER', 1, 499, 'ký tự', 'SECURITY', 56,
     '[Lý do thao tác] Số ký tự tối thiểu của lý do khi huỷ, từ chối, hoàn thành đơn, đổi quy tắc, cấp truy cập, sửa cấu hình… (không tính khoảng trắng hai đầu)', true),
    ('REASON_MAX_LENGTH', '500', 'INTEGER', 2, 500, 'ký tự', 'SECURITY', 57,
     '[Lý do thao tác] Số ký tự tối đa của lý do (không tính khoảng trắng hai đầu); không vượt quá 500, độ dài cột lưu lý do', true)
ON CONFLICT (config_key) DO NOTHING;
