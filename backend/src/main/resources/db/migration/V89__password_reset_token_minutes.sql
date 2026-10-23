-- V89: hạn của link đặt lại mật khẩu vào cấu hình hệ thống (M-03, bỏ plusMinutes(15) viết cứng ở AuthService
-- và câu "15 phút" viết cứng trong mail). Đọc lúc tạo token: đổi giá trị không ảnh hưởng link đã gửi.
-- Cùng nhóm SECURITY (display_order 1, 40–57 đã dùng).
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
    ('PASSWORD_RESET_TOKEN_MINUTES', '15', 'INTEGER', 5, 60, 'phút', 'SECURITY', 58,
     '[Đặt lại mật khẩu] Thời gian hiệu lực của link đặt lại mật khẩu gửi qua e-mail, tính từ lúc gửi yêu cầu. Chỉ áp dụng cho link gửi sau khi sửa', true)
ON CONFLICT (config_key) DO NOTHING;
