-- ============================================================================
-- V79: Tham số quyền vào khu của bảo vệ đang xử lý sự cố (BR-GD-02 / BR-GD-03)
-- ============================================================================
-- GUARD đã tiếp nhận (claim) sự cố tại khu X được camera cho vào X, nguồn INCIDENT_RESPONSE, không cần đơn.
-- Chỉ áp cho khu GUARD không tự vào được bằng cấp độ (cấp 3, hoặc khu có cờ explicit_authorization_required).
-- Cửa sổ quyền [claimed_at, mốc kết thúc), mốc kết thúc không tính:
--   chưa xử lý xong: claimed_at + GUARD_INCIDENT_ACCESS_MINUTES
--   đã xử lý xong:   min(claimed_at + GUARD_INCIDENT_ACCESS_MINUTES, resolved_at + GUARD_INCIDENT_EXIT_GRACE_MINUTES)
-- AccessDecisionService đọc mỗi lần xét quyền nên sửa có hiệu lực ngay.
-- Chỉ thêm khoá cấu hình, không seed dữ liệu khác. Cùng nhóm SECURITY (display_order 40–51 đã dùng).

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
    ('GUARD_INCIDENT_ACCESS_MINUTES', '30', 'INTEGER', 5, 240, 'phút', 'SECURITY', 52,
     '[Bảo vệ xử lý sự cố] Bảo vệ đã tiếp nhận sự cố được vào khu vực có sự cố trong bao nhiêu phút kể từ lúc tiếp nhận', true),
    ('GUARD_INCIDENT_EXIT_GRACE_MINUTES', '5', 'INTEGER', 0, 30, 'phút', 'SECURITY', 53,
     '[Bảo vệ xử lý sự cố] Sau khi xử lý xong sự cố, bảo vệ còn được qua camera của khu vực trong bao nhiêu phút để rời khu (không vượt quá thời hạn tiếp nhận)', true)
ON CONFLICT (config_key) DO NOTHING;
