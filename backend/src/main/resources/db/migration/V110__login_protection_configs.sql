-- V110 (PR-AU): tham số chống dò / dò mật khẩu khi đăng nhập vào cấu hình hệ thống.
-- Đọc qua SystemConfigService mỗi lần đăng nhập: sửa trên màn Cấu hình hệ thống có hiệu lực ngay, không restart.
--   LOGIN_MAX_FAILED_ATTEMPTS   : số lần đăng nhập sai (theo e-mail) trong cửa sổ thì khoá tạm
--   LOGIN_FAILED_WINDOW_MINUTES : độ dài cửa sổ đếm lần sai
--   LOGIN_LOCK_MINUTES          : thời gian khoá tạm
--   LOGIN_IP_RATE_PER_MINUTE    : số lần gọi /api/auth/login, /google, /send-reset-link, /reset-password từ 1 IP trong 1 phút
-- Cùng nhóm SECURITY (display_order 58–62 đã dùng).
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
    ('LOGIN_MAX_FAILED_ATTEMPTS', '5', 'INTEGER', 2, 20, 'lần', 'SECURITY', 63,
     '[Đăng nhập] Số lần đăng nhập sai liên tiếp của cùng một e-mail trong cửa sổ đếm thì tài khoản bị khoá đăng nhập tạm thời', true),
    ('LOGIN_FAILED_WINDOW_MINUTES', '15', 'INTEGER', 1, 60, 'phút', 'SECURITY', 64,
     '[Đăng nhập] Độ dài cửa sổ đếm số lần đăng nhập sai của một e-mail', true),
    ('LOGIN_LOCK_MINUTES', '15', 'INTEGER', 1, 1440, 'phút', 'SECURITY', 65,
     '[Đăng nhập] Thời gian khoá đăng nhập tạm thời sau khi sai quá số lần cho phép. Trong thời gian khoá, nhập đúng mật khẩu cũng bị từ chối', true),
    ('LOGIN_IP_RATE_PER_MINUTE', '20', 'INTEGER', 5, 600, 'lần/phút', 'SECURITY', 66,
     '[Đăng nhập] Số lần gọi đăng nhập, đăng nhập Google, gửi link và đặt lại mật khẩu tối đa từ một địa chỉ IP trong 1 phút; vượt ngưỡng trả 429', true)
ON CONFLICT (config_key) DO NOTHING;
