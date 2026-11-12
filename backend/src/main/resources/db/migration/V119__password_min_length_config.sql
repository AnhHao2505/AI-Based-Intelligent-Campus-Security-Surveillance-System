-- V119 (BE-06): độ dài tối thiểu của mật khẩu mới do người dùng tự đặt. Hiện chỉ có một luồng là đặt lại mật khẩu qua link
-- (PUT /api/auth/reset-password); hệ thống chưa có màn đổi mật khẩu khi đã đăng nhập.
-- Trước đây DTO chỉ @NotBlank. Lucas chốt 12/11: mặc định 8, cho sửa 6–64; quy tắc độ mạnh cố định "có ít nhất 1 chữ cái
-- và 1 chữ số" (kiểm ở AuthService). Mật khẩu hệ thống tự sinh cũng luôn có chữ + số (TemporaryPasswordGenerator).
-- Mật khẩu đang có không bị kiểm lại.
-- Cùng nhóm SECURITY (display_order 58–66 đã dùng).
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
    ('PASSWORD_MIN_LENGTH', '8', 'INTEGER', 6, 64, 'ký tự', 'SECURITY', 67,
     '[Mật khẩu] Số ký tự tối thiểu của mật khẩu mới do người dùng tự đặt (đặt lại mật khẩu). Mật khẩu mới còn phải có ít nhất 1 chữ cái và 1 chữ số. Mật khẩu tạm hệ thống cấp dài max(12, giá trị này). Mật khẩu đang có không bị kiểm lại', true)
ON CONFLICT (config_key) DO NOTHING;
