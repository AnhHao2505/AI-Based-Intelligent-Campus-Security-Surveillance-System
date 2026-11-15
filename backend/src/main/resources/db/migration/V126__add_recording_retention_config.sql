-- Migration V126: Thêm tham số cấu hình thời hạn lưu trữ video ghi hình camera (RECORDING_RETENTION_HOURS)
-- Chống hardcode 30 đoạn / 30 phút theo cẩm nang 6.1 (thời hạn lưu trữ video phải cấu hình được trên màn Admin, có hiệu lực ngay).
-- Giá trị mặc định: 0.5 (giờ) tương đương 30 phút hiện tại. Cho phép sửa từ 0.1 giờ (6 phút) đến 720.0 giờ (30 ngày).
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
    ('RECORDING_RETENTION_HOURS', '0.5', 'DECIMAL', 0.1, 720.0, 'giờ', 'AI_CONFIG', 12,
     'Thời gian lưu trữ video ghi hình camera định kỳ trước khi tự động xoá xoay vòng (giờ)', true)
ON CONFLICT (config_key) DO NOTHING;
