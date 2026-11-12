-- V120 (N1, đợt kiểm 12/11): độ dài mục đích lượt khách (BR-GV-03) chuyển từ số viết cứng sang cấu hình.
-- Trước đây: GuestVisitService kiểm 10–500 viết cứng, ERR_GUEST_006 viết cứng "10 đến 500", và CHECK
-- chk_guest_visits_purpose (V60) khoá cứng 10–500 trong DB. Nay GUEST_PURPOSE_MIN_LENGTH / GUEST_PURPOSE_MAX_LENGTH
-- (mặc định 10 / 500 = như cũ), kiểm ở GuestVisitService, kiểm chéo min < max ≤ 500 ở SystemConfigService.
-- Bỏ CHECK vì nó chặn ngược cấu hình (hạ min xuống dưới 10 thì DB vẫn từ chối). Cột vẫn VARCHAR(500) NOT NULL
-- nên max không vượt được 500 (max_value = 500 = độ dài cột).
-- Không đụng dữ liệu đang có; giới hạn mới chỉ áp cho lượt khách tạo sau khi sửa cấu hình.
ALTER TABLE guest_visits DROP CONSTRAINT IF EXISTS chk_guest_visits_purpose;

-- Nhóm GUEST (display_order 70–78 đã dùng).
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
    ('GUEST_PURPOSE_MIN_LENGTH', '10', 'INTEGER', 1, 500, 'ký tự', 'GUEST', 79,
     '[Khách] Số ký tự tối thiểu của mục đích lượt khách (sau khi bỏ khoảng trắng đầu / cuối). Phải nhỏ hơn độ dài tối đa', true),
    ('GUEST_PURPOSE_MAX_LENGTH', '500', 'INTEGER', 1, 500, 'ký tự', 'GUEST', 80,
     '[Khách] Số ký tự tối đa của mục đích lượt khách. Không vượt quá 500 (độ dài cột lưu trong cơ sở dữ liệu)', true)
ON CONFLICT (config_key) DO NOTHING;
